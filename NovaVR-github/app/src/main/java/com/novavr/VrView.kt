package com.novavr

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import kotlin.math.*

/**
 * Compositor estéreo: fundo = câmera traseira (passthrough), janelas flutuantes presas ao mundo
 * (navegador, barra de endereço, teclado), cursor pela mão (pinça) ou pelo olhar.
 */
class VrView(private val act: Activity, private val browser: BrowserSurface, private val onExit: () -> Unit) :
    View(act), SensorEventListener {

    class Btn(val id: String, val r: RectF)
    class Panel(val yaw: Float, val pitch: Float, val wRad: Float, val bw: Int, val bh: Int) {
        val hRad = wRad * bh / bw
        var bmp: Bitmap? = null
        val btns = ArrayList<Btn>()
    }

    @Volatile var cam: Bitmap? = null
    @Volatile var hand: HandPose? = null

    private val W = 1.117f // largura das janelas em rad (~64°)
    private val web = Panel(0f, .03f, W, browser.w, browser.h)
    private val bar = Panel(0f, -.47f, W, 1280, 160)
    private val kb = Panel(0f, -.85f, W, 1280, 520)

    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rm = FloatArray(9)
    private var yaw = 0f; private var pitch = 0f; private var roll = 0f
    private var yawOff = 0f; private var needCenter = true

    private var text = ""; private var kbOn = false; private var hoverId = ""
    private var vx = 0f; private var vy = 0f
    private var pinching = false; private var downPanel: Panel? = null
    private var lx = 0f; private var ly = 0f
    private var dwell = 0f; private var dwellId = ""
    private var cropL = 0f; private var cropT = 0f; private var cropS = 1f
    private var lastT = System.nanoTime()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)

    init { drawBar(); drawKb() }

    // ---------- sensores ----------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }
    override fun onDetachedFromWindow() { sm.unregisterListener(this); super.onDetachedFromWindow() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        val gx = -rm[2]; val gy = -rm[5]; val gz = -rm[8]          // direção do olhar (-Z do aparelho)
        val sgn = if (act.windowManager.defaultDisplay.rotation == Surface.ROTATION_270) -1f else 1f
        val ux = sgn * rm[0]; val uy = sgn * rm[3]; val uz = sgn * rm[6] // "cima" do usuário
        yaw = atan2(gx, gy); pitch = asin(gz.coerceIn(-1f, 1f))
        val n = hypot(gx, gy).coerceAtLeast(1e-4f)
        val rx = gy / n; val ry = -gx / n
        val u0x = ry * gz; val u0y = -rx * gz; val u0z = rx * gy - ry * gx
        roll = atan2(ux * rx + uy * ry, ux * u0x + uy * u0y + uz * u0z)
        if (needCenter) { yawOff = yaw; needCenter = false }
    }

    private fun wrap(a: Float): Float { var x = a; while (x > PI) x -= 2 * PI.toFloat(); while (x < -PI) x += 2 * PI.toFloat(); return x }

    // ---------- UI desenhada ----------
    private fun key(c: Canvas, id: String, label: String, r: RectF, list: ArrayList<Btn>) {
        p.color = if (hoverId == id) 0xFF7C5CFF.toInt() else 0xFF2A3050.toInt()
        c.drawRoundRect(r, 18f, 18f, p)
        tp.textSize = 38f
        c.drawText(label, r.centerX(), r.centerY() + 13f, tp)
        list.add(Btn(id, r))
    }
    private fun drawBar() {
        val b = Bitmap.createBitmap(1280, 160, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        bar.btns.clear()
        p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1280f, 160f, 80f, 80f, p)
        p.color = 0xFF2A3050.toInt(); c.drawRoundRect(30f, 30f, 640f, 130f, 50f, 50f, p)
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 38f
        c.drawText(if (text.isEmpty()) "Digite um endereço ou busca" else text.takeLast(28), 60f, 92f, tp)
        tp.textAlign = Paint.Align.CENTER
        listOf("back" to "←", "yt" to "▶", "kb" to "ABC", "rc" to "⌖", "x" to "✕").forEachIndexed { i, (id, l) ->
            key(c, id, l, RectF(670f + i * 120f, 30f, 770f + i * 120f, 130f), bar.btns)
        }
        bar.bmp = b
    }
    private fun drawKb() {
        val b = Bitmap.createBitmap(1280, 520, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        kb.btns.clear()
        p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1280f, 520f, 40f, 40f, p)
        listOf("1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm./").forEachIndexed { j, row ->
            row.forEachIndexed { i, ch -> key(c, "k:$ch", "$ch", RectF(40f + i * 120f, 20f + j * 96f, 152f + i * 120f, 104f + j * 96f), kb.btns) }
        }
        key(c, "k:sp", "Espaço", RectF(40f, 404f, 480f, 488f), kb.btns)
        key(c, "k:del", "Apagar", RectF(500f, 404f, 760f, 488f), kb.btns)
        key(c, "k:go", "Ir", RectF(780f, 404f, 1040f, 488f), kb.btns)
        kb.bmp = b
    }

    private fun go() {
        val t = text.trim(); if (t.isEmpty()) return
        val u = when {
            t.startsWith("http") -> t
            t.contains(' ') || !t.contains('.') -> "https://www.youtube.com/results?search_query=" + Uri.encode(t)
            else -> "https://$t"
        }
        browser.load(u); kbOn = false
    }
    private fun onButton(id: String) {
        when {
            id == "back" -> browser.back()
            id == "yt" -> browser.load("https://www.youtube.com/")
            id == "kb" -> kbOn = !kbOn
            id == "rc" -> needCenter = true
            id == "x" -> onExit()
            id == "k:del" -> text = text.dropLast(1)
            id == "k:sp" -> text += " "
            id == "k:go" -> go()
            id.startsWith("k:") -> text += id.substring(2)
        }
        drawBar()
    }

    // ---------- geometria ----------
    private fun px(q: Panel, yw: Float, ppr: Float) = wrap(q.yaw - yw) * ppr
    private fun py(q: Panel, ppr: Float) = -(q.pitch - pitch) * ppr
    private fun hit(x: Float, y: Float, ppr: Float, yw: Float): Triple<Panel, Float, Float>? {
        for (q in if (kbOn) listOf(kb, bar, web) else listOf(bar, web)) {
            val u = (x - px(q, yw, ppr)) / (q.wRad * ppr) + .5f
            val v = (y - py(q, ppr)) / (q.hRad * ppr) + .5f
            if (u in 0f..1f && v in 0f..1f) return Triple(q, u * q.bw, v * q.bh)
        }
        return null
    }

    // ---------- interação: mão (pinça) ou olhar (permanência) ----------
    private fun interact(ppr: Float, yw: Float, dt: Float, ew: Float, h: Int) {
        val hd = hand
        vx = 0f; vy = 0f                      // o cursor e o ponto no centro da visao (mira com a cabeca)
        val ht = hit(0f, 0f, ppr, yw)
        val id = ht?.let { t -> t.first.btns.firstOrNull { it.r.contains(t.second, t.third) }?.id } ?: ""
        if (id != hoverId) { hoverId = id; drawBar(); drawKb() }
        if (ht != null && ht.first === web) { lx = ht.second; ly = ht.third }
        val pinch = hd?.pinch == true
        if (pinch && !pinching) {
            pinching = true; downPanel = ht?.first; dwell = -.8f
            if (ht != null) { if (ht.first === web) browser.touch(MotionEvent.ACTION_DOWN, lx, ly) else if (id.isNotEmpty()) onButton(id) }
        } else if (pinch) {
            if (downPanel === web) browser.touch(MotionEvent.ACTION_MOVE, lx, ly)
        } else if (pinching) {
            pinching = false; if (downPanel === web) browser.touch(MotionEvent.ACTION_UP, lx, ly); downPanel = null
        }
        if (!pinching) {
            if (id.isNotEmpty() && id == dwellId) dwell += dt else dwell = 0f
            dwellId = id
            if (dwell >= 1.2f) { onButton(id); dwell = -.8f }
        }
    }

    // Toque na tela = clique no ponto do olhar (útil se o VR Box tiver botão)
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN && hand == null) {
            if (hoverId.isNotEmpty()) onButton(hoverId)
            else { browser.touch(MotionEvent.ACTION_DOWN, lx, ly); browser.touch(MotionEvent.ACTION_UP, lx, ly) }
        }
        return true
    }

    // ---------- desenho ----------
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height; val ew = w / 2f
        val ppr = ew / 1.5708f                 // 90° de campo por olho
        val yw = wrap(yaw - yawOff)
        val now = System.nanoTime(); val dt = ((now - lastT) / 1e9f).coerceAtMost(.1f); lastT = now

        val cb = cam
        if (cb != null) {
            cropS = max(ew / cb.width, h / cb.height.toFloat())
            cropL = (cb.width - ew / cropS) / 2f; cropT = (cb.height - h / cropS) / 2f
        }
        val frame = browser.poll()
        interact(ppr, yw, dt, ew, h)

        c.drawColor(0xFF05060C.toInt())
        for (eye in 0..1) {
            c.save(); c.clipRect(eye * ew, 0f, (eye + 1) * ew, h.toFloat())
            c.translate(eye * ew + ew / 2f, h / 2f)
            if (cb != null) {
                c.drawBitmap(cb, Rect(cropL.toInt(), cropT.toInt(), (cropL + ew / cropS).toInt(), (cropT + h / cropS).toInt()),
                    RectF(-ew / 2f, -h / 2f, ew / 2f, h / 2f), bp)
            }
            tp.textAlign = Paint.Align.CENTER; tp.textSize = 26f
            c.drawText("camera: " + (if (cb != null) "ok" else "nao") + "   mao: " + (if (hand != null) "ok" else "nao") + (if (pinching) "   PINCA" else ""), 0f, -h / 2f + 60f, tp)
            c.rotate(-Math.toDegrees(roll.toDouble()).toFloat())
            val par = if (eye == 0) 14f else -14f
            val list = if (kbOn) listOf(web, bar, kb) else listOf(web, bar)
            for (q in list) {
                val cx = px(q, yw, ppr) + par; val cy = py(q, ppr)
                val dst = RectF(cx - q.wRad * ppr / 2, cy - q.hRad * ppr / 2, cx + q.wRad * ppr / 2, cy + q.hRad * ppr / 2)
                if (q === web) {
                    if (frame != null) c.drawBitmap(frame, Rect(0, 0, browser.w, browser.h), dst, bp)
                    else { p.color = 0xFF151927.toInt(); c.drawRoundRect(dst, 30f, 30f, p) }
                } else q.bmp?.let { c.drawBitmap(it, null, dst, bp) }
            }
            // cursor: sempre no centro da visao
            val onPanel = hit(0f, 0f, ppr, yw) != null
            val cxr = if (onPanel) par else 0f
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f
            p.color = if (pinching) 0xFF22C55E.toInt() else Color.WHITE
            c.drawCircle(cxr, 0f, 16f, p)
            if (dwell > 0f) { p.color = 0xFF9D86FF.toInt(); c.drawArc(RectF(cxr - 24f, -24f, cxr + 24f, 24f), -90f, 360f * dwell / 1.2f, false, p) }
            p.style = Paint.Style.FILL
            c.restore()
        }
        postInvalidateOnAnimation()
    }
}
