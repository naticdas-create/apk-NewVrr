package com.novavr

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var vr: VrGlView
    private lateinit var browser: BrowserSurface
    private var tracker: HandTracker? = null
    private var ar: ArTracker? = null
    private var fcount = 0
    private val exec = Executors.newSingleThreadExecutor()
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
        }
        browser = BrowserSurface(this, 1024, 576)
        vr = VrGlView(this, browser) { finish() }
        setContentView(vr)
        browser.start("https://www.youtube.com/")
        perm.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        tracker = try { HandTracker(this) } catch (e: Exception) {
            Toast.makeText(this, "Modelo de mãos ausente: usando só o olhar", Toast.LENGTH_LONG).show(); null
        }
        if (ArTracker.supported(this)) {
            val a = ArTracker(this, tracker) { b, h -> vr.cam = b; vr.hands = h }
            if (a.create()) { ar = a; vr.ar = a; a.resume(); return }
            Toast.makeText(this, "ARCore falhou: sem tracking de posicao", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "ARCore indisponivel: instale Servicos do Google Play para RA", Toast.LENGTH_LONG).show()
        }
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { px ->
                var b: Bitmap = px.toBitmap()
                val rot = px.imageInfo.rotationDegrees
                if (rot != 0) {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                }
                px.close()
                vr.cam = b
                fcount++
                if (fcount % 2 == 0) vr.hands = try { tracker?.detect(b) ?: emptyList() } catch (e: Exception) { emptyList() }
            }
            val p = f.get()
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    override fun onResume() { super.onResume(); vr.onResume(); ar?.resume() }
    override fun onPause() { ar?.pause(); vr.onPause(); super.onPause() }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown(); tracker?.close(); browser.release()
    }
}
