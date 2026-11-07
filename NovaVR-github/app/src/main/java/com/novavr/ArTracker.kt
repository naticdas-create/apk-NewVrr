package com.novavr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Posicao real do usuario (6DoF) via ARCore. A rotacao continua vindo do giroscopio (baixa latencia);
 * daqui so usamos a translacao. Tambem entrega os quadros da camera (fundo + maos), ja que o ARCore
 * ocupa a camera traseira.
 */
class ArTracker(
    private val act: Activity,
    private val tracker: HandTracker?,
    private val onImage: (Bitmap, List<Hand>) -> Unit
) {

    companion object {
        fun supported(act: Activity): Boolean {
            return try {
                var av = ArCoreApk.getInstance().checkAvailability(act)
                var tries = 0
                while (av.isTransient && tries < 10) {
                    Thread.sleep(200)
                    av = ArCoreApk.getInstance().checkAvailability(act)
                    tries++
                }
                av == ArCoreApk.Availability.SUPPORTED_INSTALLED
            } catch (e: Exception) {
                false
            }
        }
    }

    private var session: Session? = null
    private var texId = 0
    private var glReady = false
    private var rotDeg = 0
    private var lastTs = 0L
    private var n = 0
    private var lastHands: List<Hand> = emptyList()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    @Volatile
    var tracking = false
        private set

    @Volatile
    var status = "iniciando"
        private set

    val pos = FloatArray(3)
    val fwd = FloatArray(3)

    fun create(): Boolean {
        return try {
            val s = Session(act)
            val c = Config(s)
            c.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            c.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)
            c.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            c.setFocusMode(Config.FocusMode.AUTO)
            s.configure(c)
            session = s
            true
        } catch (e: Exception) {
            status = "indisponivel"
            false
        }
    }

    fun resume() {
        try { session?.resume() } catch (e: Exception) { status = "camera ocupada" }
    }

    fun pause() {
        try { session?.pause() } catch (e: Exception) { }
    }

    /** Chamar na thread do OpenGL. */
    fun initGl() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session?.setCameraTextureName(texId)
        glReady = true
    }

    fun setDisplay(rotation: Int, w: Int, h: Int) {
        try {
            session?.setDisplayGeometry(rotation, w, h)
            val camId = session?.cameraConfig?.cameraId ?: "0"
            val mgr = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sens = mgr.getCameraCharacteristics(camId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val dd = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            rotDeg = (sens - dd + 360) % 360
        } catch (e: Exception) {
            rotDeg = 0
        }
    }

    /** Chamar a cada quadro, na thread do OpenGL. */
    fun update() {
        val s = session ?: return
        if (!glReady) return
        val frame = try { s.update() } catch (e: Exception) { return }
        val ts = frame.timestamp
        if (ts == lastTs) return                      // mesmo quadro da camera: nada novo
        lastTs = ts
        val cam = frame.camera
        tracking = cam.trackingState == TrackingState.TRACKING
        status = when (cam.trackingState) {
            TrackingState.TRACKING -> "rastreando"
            TrackingState.PAUSED -> "procurando (mova o celular devagar)"
            else -> "parado"
        }
        if (tracking) {
            val p = cam.displayOrientedPose
            pos[0] = p.tx(); pos[1] = p.ty(); pos[2] = p.tz()
            val z = p.zAxis
            fwd[0] = -z[0]; fwd[1] = -z[1]; fwd[2] = -z[2]
        }
        if (busy.compareAndSet(false, true)) {
            try {
                val img = frame.acquireCameraImage()
                worker.execute {
                    try { process(img) } catch (e: Exception) { } finally { img.close(); busy.set(false) }
                }
            } catch (e: Exception) {
                busy.set(false)
            }
        }
    }

    private fun process(img: Image) {
        val w = img.width
        val h = img.height
        val nv = toNv21(img)
        val bos = ByteArrayOutputStream()
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 80, bos)
        val bytes = bos.toByteArray()
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        if (rotDeg != 0) {
            val m = Matrix()
            m.postRotate(rotDeg.toFloat())
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        n++
        if (n % 2 == 0) {                              // maos: metade da taxa, para poupar CPU
            lastHands = try { tracker?.detect(b) ?: emptyList() } catch (e: Exception) { emptyList() }
        }
        onImage(b, lastHands)
    }

    /** YUV_420_888 -> NV21, com caminho rapido quando os planos ja estao intercalados. */
    private fun toNv21(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val out = ByteArray(w * h * 3 / 2)
        val yb = yP.buffer
        if (yP.rowStride == w) {
            yb.rewind()
            yb.get(out, 0, w * h)
        } else {
            for (j in 0 until h) {
                yb.position(j * yP.rowStride)
                yb.get(out, j * w, w)
            }
        }
        val vb = vP.buffer
        val ub = uP.buffer
        var o = w * h
        if (vP.pixelStride == 2 && uP.pixelStride == 2 && vP.rowStride == w) {
            vb.rewind()
            val cnt = minOf(vb.remaining(), w * h / 2)
            vb.get(out, o, cnt)
        } else {
            for (j in 0 until h / 2) {
                for (i in 0 until w / 2) {
                    out[o++] = vb.get(j * vP.rowStride + i * vP.pixelStride)
                    out[o++] = ub.get(j * uP.rowStride + i * uP.pixelStride)
                }
            }
        }
        return out
    }
}
