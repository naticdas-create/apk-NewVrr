package com.novavr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.hypot

/** pts = 21 pontos (x,y normalizados) da mao; pinch = polegar encostando no indicador. */
class Hand(val left: Boolean, val pts: FloatArray, val pinch: Boolean)

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
    private var last: List<Hand> = emptyList()
    private var lastSeen = 0L

    fun detect(b: Bitmap): List<Hand> {
        val now = SystemClock.uptimeMillis()
        val r = lm.detectForVideo(BitmapImageBuilder(b).build(), now)
        val out = ArrayList<Hand>()
        r.landmarks().forEachIndexed { i, h ->
            val left = r.handedness().getOrNull(i)?.firstOrNull()?.categoryName() == "Left"
            val k = if (left) 0 else 1
            fun d(a: Int, c: Int) = hypot(h[a].x() - h[c].x(), h[a].y() - h[c].y())
            val ratio = d(4, 8) / d(0, 9).coerceAtLeast(1e-3f)
            pin[k] = if (pin[k]) ratio < 0.65f else ratio < 0.4f
            val pts = FloatArray(42)
            for (j in 0 until 21) { pts[2 * j] = h[j].x(); pts[2 * j + 1] = h[j].y() }
            out.add(Hand(left, pts, pin[k]))
        }
        if (out.isEmpty()) return if (now - lastSeen < 150) last else out
        last = out; lastSeen = now
        return out
    }

    fun close() = lm.close()
}
