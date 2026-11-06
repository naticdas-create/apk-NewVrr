package com.novavr

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Roda um WebView numa tela virtual (Presentation) e entrega os quadros como Bitmap.
 * Assim o vídeo do YouTube (que usa camada de hardware) aparece corretamente.
 */
class BrowserSurface(private val ctx: Context, val w: Int = 1280, val h: Int = 720) {
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
    private val vd = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).createVirtualDisplay(
        "nova-browser", w, h, 160, reader.surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    )
    private var pres: Pres? = null
    private var bmp: Bitmap? = null
    private var downTime = 0L
    @Volatile var version = 0

    private class Pres(c: Context, d: Display, val first: String) : Presentation(c, d) {
        lateinit var web: WebView
        override fun onCreate(s: Bundle?) {
            super.onCreate(s)
            web = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString =
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(first)
            }
            setContentView(web)
        }
    }

    fun start(url: String) { pres = Pres(ctx, vd.display, url).also { it.show() } }
    fun load(url: String) { pres?.web?.loadUrl(url) }
    fun back() { pres?.web?.let { if (it.canGoBack()) it.goBack() } }

    /** Chame na thread de UI; devolve o quadro mais recente (largura do bitmap pode ser maior que w). */
    fun poll(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return bmp
        val pl = img.planes[0]
        val stride = pl.rowStride / pl.pixelStride
        val b = bmp ?: Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888).also { bmp = it }
        pl.buffer.rewind()
        b.copyPixelsFromBuffer(pl.buffer)
        img.close()
        version++
        return b
    }

    fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        pres?.window?.decorView?.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun release() { pres?.dismiss(); vd.release(); reader.close() }
}
