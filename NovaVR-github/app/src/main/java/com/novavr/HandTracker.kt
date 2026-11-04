package com.novavr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.hypot

/** x,y normalizados (0..1) no quadro da câmera; pinch = polegar encostando no indicador. */
class HandPose(val x: Float, val y: Float, val pinch: Boolean)

class HandTracker(ctx: Context) {
    private val lm: HandLandmarker = HandLandmarker.createFromOptions(
        ctx,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setNumHands(1)
            .setRunningMode(RunningMode.VIDEO)
            .build()
    )
    private var pinching = false
    private var last: HandPose? = null
    private var lastSeen = 0L

    fun detect(b: Bitmap): HandPose? {
        val now = SystemClock.uptimeMillis()
        val res = lm.detectForVideo(BitmapImageBuilder(b).build(), now)
        val h = res.landmarks().firstOrNull()
        if (h == null) return if (now - lastSeen < 150) last else null
        fun d(a: Int, c: Int) = hypot(h[a].x() - h[c].x(), h[a].y() - h[c].y())
        val ratio = d(4, 8) / d(0, 9).coerceAtLeast(1e-3f)
        pinching = if (pinching) ratio < 0.65f else ratio < 0.4f
        val p = HandPose((h[4].x() + h[8].x()) / 2f, (h[4].y() + h[8].y()) / 2f, pinching)
        last = p; lastSeen = now
        return p
    }

    fun close() = lm.close()
}
