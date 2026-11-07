package com.novavr

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Andar para frente e para tras usando so a camera (sem ARCore). EXPERIMENTAL.
 *
 * Ideia: ao andar para frente a imagem "cresce" a partir do centro; ao andar para tras ela "encolhe".
 * A cada ~0,25 s comparamos o quadro atual com o anterior e procuramos qual fator de escala (e pequeno
 * deslocamento) casa melhor. Com uma profundidade tipica da cena (Z), a distancia andada e d = Z * (1 - 1/escala).
 *
 * Cuidados para nao "andar sozinho": ignora quando voce gira a cabeca rapido, quando a cena tem pouca textura,
 * exige melhora clara sobre "parado" e so aplica depois de duas medidas seguidas no mesmo sentido.
 */
class VoLite {
    companion object {
        const val W = 80
        const val H = 60
        private val SCALES = floatArrayOf(.86f, .89f, .92f, .95f, .97f, 1.0f, 1.03f, 1.05f, 1.08f, 1.11f, 1.15f)
    }

    @Volatile var enabled = true
    @Volatile var depth = 2.5f                 // profundidade tipica da cena, em metros (ajustavel)

    private val ex = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var resetFlag = false
    private var lastSample = 0L
    private var peak = 0f                      // maior velocidade de giro desde a ultima amostra (rad/s)
    private var acc = 0f                       // metros acumulados (para frente > 0)

    // usados so na thread do executor
    private var prev: FloatArray? = null
    private var prevD = 0f
    private var prevApplied = false

    @Synchronized fun setRate(r: Float) { if (r > peak) peak = r }
    @Synchronized private fun takePeak(): Float { val v = peak; peak = 0f; return v }
    @Synchronized private fun addDist(d: Float) { acc += d }
    @Synchronized fun take(): Float { val v = acc; acc = 0f; return v }

    /** Chamar com cada quadro da camera. */
    fun feed(b: Bitmap, nowMs: Long) {
        if (!enabled) { resetFlag = true; return }
        if (nowMs - lastSample < 250) return
        lastSample = nowMs
        if (!busy.compareAndSet(false, true)) return
        val rot = takePeak()
        val g: FloatArray
        try {
            val small = Bitmap.createScaledBitmap(b, W, H, true)
            val px = IntArray(W * H)
            small.getPixels(px, 0, W, 0, 0, W, H)
            g = FloatArray(W * H)
            for (i in 0 until W * H) {
                val c = px[i]
                g[i] = ((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f
            }
        } catch (e: Exception) {
            busy.set(false)
            return
        }
        ex.execute {
            try { work(g, rot) } catch (e: Exception) { } finally { busy.set(false) }
        }
    }

    private fun work(c: FloatArray, rot: Float) {
        if (resetFlag) { prev = null; prevD = 0f; prevApplied = false; resetFlag = false }
        val p = prev
        prev = c
        if (p == null) return
        if (rot > 0.5f) { prevD = 0f; prevApplied = false; return }        // girando a cabeca: ignora
        val s = estimateScale(p, c)
        if (s == 1f) { prevD = 0f; prevApplied = false; return }
        var d = depth * (1f - 1f / s)
        if (d > 0.5f) d = 0.5f
        if (d < -0.5f) d = -0.5f
        if (prevD != 0f && (prevD > 0f) == (d > 0f)) {                     // duas medidas seguidas no mesmo sentido
            addDist(if (prevApplied) d else d + prevD)
            prevApplied = true
        } else {
            prevApplied = false
        }
        prevD = d
    }

    private fun samp(p: FloatArray, x: Float, y: Float): Float {
        val x0 = x.toInt(); val y0 = y.toInt()
        val fx = x - x0; val fy = y - y0
        val i = y0 * W + x0
        return (p[i] * (1f - fx) + p[i + 1] * fx) * (1f - fy) + (p[i + W] * (1f - fx) + p[i + W + 1] * fx) * fy
    }

    /** Devolve a escala (>1 = andou para frente, <1 = para tras, 1 = parado/incerto). */
    private fun estimateScale(p: FloatArray, c: FloatArray): Float {
        val cx = W / 2f; val cy = H / 2f
        val xs = 14; val xe = 66; val ys = 10; val ye = 50
        var n = 0; var mc = 0f; var tex = 0f
        for (y in ys until ye step 2) {
            for (x in xs until xe step 2) {
                val v = c[y * W + x]
                mc += v; n++
                tex += abs(c[y * W + x + 1] - v)
            }
        }
        mc /= n; tex /= n
        if (tex < 3f) return 1f                                            // parede lisa / escuro: nao da para medir
        val tmp = FloatArray(n)
        var best = Float.MAX_VALUE; var bestS = 1f; var sad1 = Float.MAX_VALUE
        for (s in SCALES) {
            for (dy in -2..2) {
                for (dx in -3..3) {
                    var k = 0; var mp = 0f
                    for (y in ys until ye step 2) {
                        for (x in xs until xe step 2) {
                            val v = samp(p, cx + (x - cx - dx) / s, cy + (y - cy - dy) / s)
                            tmp[k++] = v; mp += v
                        }
                    }
                    mp /= n
                    var sad = 0f; k = 0
                    for (y in ys until ye step 2) {
                        for (x in xs until xe step 2) {
                            sad += abs((c[y * W + x] - mc) - (tmp[k++] - mp))
                        }
                    }
                    if (sad < best) { best = sad; bestS = s }
                    if (s == 1.0f && sad < sad1) sad1 = sad
                }
            }
        }
        return if (bestS != 1f && best < sad1 * 0.92f) bestS else 1f
    }
}
