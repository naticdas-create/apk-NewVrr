package com.novavr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * pts = 21 pontos (x,y normalizados) da mao.
 * pinch = pinca do indicador (estado confirmado); strength = progresso da pinca de 0 a 1;
 * mid/ring/pinky = pinca do dedo medio/anelar/minimo; conf = confianca do rastreio (0 a 1).
 */
class Hand(
    val left: Boolean,
    val pts: FloatArray,
    val pinch: Boolean,
    val strength: Float = 0f,
    val mid: Boolean = false,
    val ring: Boolean = false,
    val pinky: Boolean = false,
    val conf: Float = 1f
)

/**
 * Filtro One Euro: parado = bem firme (sem tremor), em movimento = acompanha rapido (sem atraso).
 * minCut baixo = mais firme; beta alto = mais rapido ao mover.
 */
private class OneEuro(private val minCut: Float, private val beta: Float) {
    private var xp = 0f
    private var dxp = 0f
    private var on = false
    private fun alpha(cut: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cut)
        return 1f / (1f + tau / dt)
    }
    fun reset() { on = false }
    fun filter(x: Float, dt: Float): Float {
        if (!on) { on = true; xp = x; dxp = 0f; return x }
        val dx = (x - xp) / dt
        dxp += alpha(1f, dt) * (dx - dxp)
        xp += alpha(minCut + beta * abs(dxp), dt) * (x - xp)
        return xp
    }
}

class HandTracker(ctx: Context) {
    private val lm: HandLandmarker = HandLandmarker.createFromOptions(
        ctx,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setNumHands(2)
            .setRunningMode(RunningMode.VIDEO)
            .build()
    )
    private val pin = BooleanArray(2)
    private val pinM = BooleanArray(2)
    private val pinR = BooleanArray(2)
    private val pinP = BooleanArray(2)
    private val sm = Array(2) { FloatArray(42) }              // pontos filtrados (saida)
    private val flt = Array(2) { Array(42) { OneEuro(1.4f, 30f) } }
    private var last: List<Hand> = emptyList()
    private var lastSeen = 0L
    private var lastDet = 0L

    fun detect(b: Bitmap): List<Hand> {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDet == 0L) .033f else ((now - lastDet) / 1000f).coerceIn(.008f, .1f)
        lastDet = now
        val r = lm.detectForVideo(BitmapImageBuilder(b).build(), now)
        val out = ArrayList<Hand>()
        val seen = BooleanArray(2)
        val lms = r.landmarks()
        val hds = r.handedness()
        lms.forEachIndexed { i, h ->
            val cat = hds.getOrNull(i)?.firstOrNull()
            val left = cat?.categoryName() == "Left"
            val conf = cat?.score() ?: 1f
            val k = if (left) 0 else 1
            seen[k] = true
            fun d(a: Int, c: Int) = hypot(h[a].x() - h[c].x(), h[a].y() - h[c].y())
            val palm = d(0, 9).coerceAtLeast(1e-3f)
            val rI = d(4, 8) / palm
            val rM = d(4, 12) / palm
            val rR = d(4, 16) / palm
            val rP = d(4, 20) / palm
            pin[k] = if (pin[k]) rI < 0.65f else rI < 0.4f
            // os outros dedos so contam se forem o mais proximo do polegar (evita confundir com a pinca do indicador)
            pinM[k] = if (pinM[k]) rM < 0.65f else (rM < 0.4f && rM < rI - 0.05f)
            pinR[k] = if (pinR[k]) rR < 0.65f else (rR < 0.4f && rR < rI - 0.05f && rR < rM - 0.05f)
            pinP[k] = if (pinP[k]) rP < 0.65f else (rP < 0.4f && rP < rI - 0.05f && rP < rM - 0.05f && rP < rR - 0.05f)
            val strength = ((0.7f - rI) / 0.5f).coerceIn(0f, 1f)
            val sp = sm[k]
            for (j in 0 until 21) {
                sp[2 * j] = flt[k][2 * j].filter(h[j].x(), dt)
                sp[2 * j + 1] = flt[k][2 * j + 1].filter(h[j].y(), dt)
            }
            out.add(Hand(left, sp.copyOf(), pin[k], strength, pinM[k] && !pin[k], pinR[k] && !pin[k], pinP[k] && !pin[k], conf))
        }
        for (k in 0..1) if (!seen[k]) for (f in flt[k]) f.reset()
        if (out.isEmpty()) {
            // perdeu o rastreio: a mao some aos poucos (como no Quest) em vez de piscar
            val age = now - lastSeen
            if (age >= 200 || last.isEmpty()) return out
            return last.map { Hand(it.left, it.pts, false, 0f, false, false, false, it.conf * (1f - age / 200f)) }
        }
        last = out
        lastSeen = now
        return out
    }

    fun close() = lm.close()
}
