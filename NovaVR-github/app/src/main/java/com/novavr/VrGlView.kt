package com.novavr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix as GLM
import android.view.MotionEvent
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: FloatArray, b: FloatArray) =
    floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun norm(a: FloatArray): FloatArray {
    val l = sqrt(dot(a, a)).coerceAtLeast(1e-6f)
    return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
}

class Btn(val id: String, val r: RectF)

/** Janela 3D: posicao e orientacao fixas no mundo. Conteudo + barra de titulo. */
class Win(val id: String, val w: Float, val h: Float, val bw: Int, val bh: Int, val hasTitle: Boolean = true) {
    var title = ""
    val pos = floatArrayOf(0f, 0f, -2f)
    var r = floatArrayOf(1f, 0f, 0f)
    var u = floatArrayOf(0f, 1f, 0f)
    var n = floatArrayOf(0f, 0f, 1f)
    var scale = 1f
    var visible = true
    var bmp: Bitmap? = null
    var tex = 0
    var dirty = true
    var uScale = 1f
    val btns = ArrayList<Btn>()
    val tbw = (w * 600).toInt()
    val tbh = 60
    var tBmp: Bitmap? = null
    var tTex = 0
    var tDirty = true
    val tbBtns = ArrayList<Btn>()
    var sd = 2f      // distancia ao abrir
    var sy = 0f      // altura ao abrir
    var sa = 0f      // angulo ao abrir (graus)
    val thM get() = 0.1f * scale
}

private const val VS_Q = """
attribute vec2 aPos; uniform mat4 uMVP; uniform vec4 uUV; varying vec2 vUV;
void main(){ gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
  vUV = uUV.xy + vec2(aPos.x + 0.5, 0.5 - aPos.y) * uUV.zw; }"""
private const val FS_Q = """
precision mediump float; uniform sampler2D uTex; uniform float uA; varying vec2 vUV;
void main(){ gl_FragColor = texture2D(uTex, vUV) * uA; }"""
private const val VS_D = """
attribute vec2 aPos; varying vec2 vUV;
void main(){ gl_Position = vec4(aPos * 2.0, 0.0, 1.0); vUV = aPos + 0.5; }"""
private const val FS_D = """
precision highp float; uniform sampler2D uTex; uniform float uK1; uniform float uK2; uniform float uZ; varying vec2 vUV;
void main(){
  float eye = step(0.5, vUV.x);
  vec2 l = vec2((vUV.x - eye * 0.5) * 2.0, vUV.y);
  vec2 c = (l - 0.5) * 2.0;
  float r2 = dot(c, c) * 0.5;
  vec2 s = c * (1.0 + uK1 * r2 + uK2 * r2 * r2) * uZ;
  if (abs(s.x) > 1.0 || abs(s.y) > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
  vec2 uv = vec2((s.x * 0.5 + 0.5) * 0.5 + eye * 0.5, s.y * 0.5 + 0.5);
  gl_FragColor = texture2D(uTex, uv);
}"""

/**
 * LUNARO VR - motor 3D (OpenGL ES 2): estereo com parallax real, correcao de lente,
 * janelas world-locked, camera de fundo, maos com laser e pinca.
 */
class VrGlView(private val act: Activity, private val browser: BrowserSurface, private val onExit: () -> Unit) :
    GLSurfaceView(act), SensorEventListener, GLSurfaceView.Renderer {

    @Volatile var cam: Bitmap? = null
    @Volatile var hands: List<Hand> = emptyList()

    // ---------- janelas ----------
    private val browserWin = Win("web", 2.2f, 2.2f * browser.h / browser.w, browser.w, browser.h).also { it.title = "Navegador"; it.sd = 2.2f; it.sy = .2f }
    private val dock = Win("dock", 1.6f, .16f, 1280, 128, false).also { it.sd = 1.8f; it.sy = -1.0f }
    private val addr = Win("addr", 2.2f, .14f, 1760, 112, false).also { it.sd = 2.2f; it.sy = .93f }
    private val apps = Win("apps", 1.3f, .9f, 1040, 720).also { it.title = "Biblioteca"; it.sd = 2.0f; it.sy = .1f; it.sa = -38f }
    private val kb = Win("kb", 1.6f, .65f, 1280, 520).also { it.title = "Teclado"; it.sd = 1.6f; it.sy = -1.1f }
    private val cfg = Win("cfg", 1f, .8f, 1000, 800).also { it.title = "Configuracoes"; it.sd = 2.0f; it.sy = .1f; it.sa = 38f }
    private val wins = listOf(browserWin, addr, dock, apps, kb, cfg)

    private class App(val id: String, val name: String, val url: String, val color: Int, val glyph: String)
    private val appList = listOf(
        App("yt", "YouTube", "https://www.youtube.com/", 0xFFE5173F.toInt(), "YT"),
        App("nf", "Netflix", "https://www.netflix.com/", 0xFFB20710.toInt(), "N"),
        App("sp", "Spotify", "https://open.spotify.com/", 0xFF1DB954.toInt(), "SP"),
        App("g", "Google", "https://www.google.com/", 0xFF2F7DE1.toInt(), "G"),
        App("dp", "Disney+", "https://www.disneyplus.com/", 0xFF113CCF.toInt(), "D+"),
        App("tw", "Twitch", "https://www.twitch.tv/", 0xFF7C5CFF.toInt(), "TW"),
        App("rb", "Roblox", "https://www.roblox.com/", 0xFFD62B2B.toInt(), "RBX"),
        App("pv", "Prime Video", "https://www.primevideo.com/", 0xFF1A98FF.toInt(), "PV"),
        App("gp", "Globoplay", "https://globoplay.globo.com/", 0xFFEF4444.toInt(), "GP"))
    private val iconBmps = HashMap<String, Bitmap>()
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun loadIcons() {
        for (id in listOf("yt", "nf", "sp", "dp", "g", "tw")) {
            try { act.assets.open("icon_$id.png").use { iconBmps[id] = BitmapFactory.decodeStream(it) } } catch (e: Exception) { }
        }
    }

    // ---------- estado ----------
    private var text = ""
    private var k1 = .22f; private var k2 = .08f; private var ipd = .064f
    private val head = floatArrayOf(0f, 0f, 0f)
    private val walkOff = FloatArray(3)          // andar virtual (botoes)
    private var F = floatArrayOf(0f, 0f, -1f); private var R = floatArrayOf(1f, 0f, 0f); private var U = floatArrayOf(0f, 1f, 0f)
    private var hoverKey = ""
    private var uiDirty = true
    @Volatile private var tap = false

    // ---------- sensores ----------
    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rm = FloatArray(9)
    @Volatile private var gazeF = floatArrayOf(0f, 0f, -1f)
    @Volatile private var gazeU = floatArrayOf(0f, 1f, 0f)
    @Volatile private var needCenter = true
    @Volatile private var yawOff = 0f

    override fun onResume() {
        super.onResume()
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }
    override fun onPause() { sm.unregisterListener(this); super.onPause() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        val gx = -rm[2]; val gy = -rm[5]; val gz = -rm[8]       // olhar = -Z do aparelho (ENU)
        val sg = if (act.windowManager.defaultDisplay.rotation == Surface.ROTATION_270) -1f else 1f
        val ux = sg * rm[0]; val uy = sg * rm[3]; val uz = sg * rm[6]
        gazeF = floatArrayOf(gx, gz, -gy)                        // ENU -> GL (X=leste, Y=cima, Z=-norte)
        gazeU = floatArrayOf(ux, uz, -uy)
        if (needCenter) { yawOff = atan2(gx, gy); needCenter = false }
    }

    private fun updateHead() {
        val a = yawOff; val ca = cos(a); val sa = sin(a)
        fun rot(v: FloatArray) = floatArrayOf(v[0] * ca + v[2] * sa, v[1], -v[0] * sa + v[2] * ca)
        val f = norm(rot(gazeF))
        val r = norm(cross(f, rot(gazeU)))
        F = f; R = r; U = cross(r, f)
    }

    // ---------- colocacao de janelas ----------
    private fun place(w: Win, p: FloatArray) {
        for (i in 0..2) w.pos[i] = p[i]
        val nrm = norm(floatArrayOf(head[0] - p[0], head[1] - p[1], head[2] - p[2]))
        var r = cross(floatArrayOf(0f, 1f, 0f), nrm)
        if (dot(r, r) < 1e-4f) r = floatArrayOf(1f, 0f, 0f)
        r = norm(r)
        w.r = r; w.n = nrm; w.u = cross(nrm, r)
    }
    private fun spawn(w: Win) {
        val a = atan2(F[0], -F[2]) + w.sa * (PI.toFloat() / 180f)
        place(w, floatArrayOf(head[0] + sin(a) * w.sd, head[1] + w.sy, head[2] - cos(a) * w.sd))
    }
    private fun toggle(w: Win) { if (w.visible) w.visible = false else { w.visible = true; spawn(w) } }
    private fun walk(fwd: Float, side: Float) {
        val fh = norm(floatArrayOf(F[0], 0f, F[2])); val rh = norm(floatArrayOf(R[0], 0f, R[2]))
        walkOff[0] += (fh[0] * fwd + rh[0] * side) * .3f
        walkOff[2] += (fh[2] * fwd + rh[2] * side) * .3f
    }

    // ---------- UI desenhada em bitmaps ----------
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hp = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun ensure(w: Win): Bitmap = w.bmp ?: Bitmap.createBitmap(w.bw, w.bh, Bitmap.Config.ARGB_8888).also { w.bmp = it }

    private fun key(c: Canvas, w: Win, id: String, label: String, r: RectF, size: Float = 36f) {
        p.style = Paint.Style.FILL
        p.color = if (hoverKey == w.id + ":" + id) 0xFF7C5CFF.toInt() else 0xFF2A3050.toInt()
        c.drawRoundRect(r, 18f, 18f, p)
        tp.textAlign = Paint.Align.CENTER; tp.textSize = size; tp.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + size * .35f, tp)
        w.btns.add(Btn(id, r))
    }

    private fun drawTitle(w: Win) {
        val b = w.tBmp ?: Bitmap.createBitmap(w.tbw, w.tbh, Bitmap.Config.ARGB_8888).also { w.tBmp = it }
        b.eraseColor(0); val c = Canvas(b); w.tbBtns.clear()
        p.style = Paint.Style.FILL; p.color = 0xEE1A1F30.toInt()
        c.drawRoundRect(0f, 0f, w.tbw.toFloat(), w.tbh.toFloat(), 24f, 24f, p)
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 30f; tp.color = Color.WHITE
        c.drawText(w.title, 24f, 40f, tp)
        listOf("size" to "<>", "min" to "-", "close" to "X").forEachIndexed { i, (id, l) ->
            val left = w.tbw - 62f - (2 - i) * 62f
            val r = RectF(left, 4f, left + 54f, 56f)
            p.color = if (hoverKey == w.id + ":" + id) 0xFF7C5CFF.toInt() else 0xFF2A3050.toInt()
            c.drawRoundRect(r, 14f, 14f, p)
            tp.textAlign = Paint.Align.CENTER; tp.textSize = 28f
            c.drawText(l, r.centerX(), r.centerY() + 10f, tp)
            w.tbBtns.add(Btn(id, r))
        }
        w.tDirty = false
    }

    private fun icon(c: Canvas, w: Win, id: String, label: String, col: Int, r: RectF, size: Float, on: Boolean = false, bmp: Bitmap? = null) {
        p.style = Paint.Style.FILL; p.color = if (bmp != null) 0xFF232840.toInt() else col; c.drawRoundRect(r, 22f, 22f, p)
        if (bmp != null) {
            val m = r.width() * .14f
            c.drawBitmap(bmp, null, RectF(r.left + m, r.top + m, r.right - m, r.bottom - m), ip)
        }
        if (hoverKey == w.id + ":" + id) { p.color = 0x55FFFFFF; c.drawRoundRect(r, 22f, 22f, p) }
        if (bmp == null) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = size; tp.color = Color.WHITE
            c.drawText(label, r.centerX(), r.centerY() + size * .35f, tp)
        }
        if (on) { p.color = Color.WHITE; c.drawCircle(r.centerX(), r.bottom + 12f, 5f, p) }
        w.btns.add(Btn(id, r))
    }

    private fun drawDock() {
        val w = dock; val b = ensure(w); b.eraseColor(0); val c = Canvas(b); w.btns.clear()
        p.style = Paint.Style.FILL; p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1280f, 128f, 64f, 64f, p)
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 34f; tp.color = Color.WHITE
        c.drawText(java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date()), 36f, 76f, tp)
        val items = ArrayList<Triple<String, String, Int>>()
        items.add(Triple("lib", "LIB", 0xFF4A5572.toInt()))
        for (a in appList.take(7)) items.add(Triple("app:" + a.id, a.glyph, a.color))
        items.add(Triple("kb", "ABC", 0xFF3B82F6.toInt()))
        items.add(Triple("cfg", "AJU", 0xFF4A5572.toInt()))
        items.add(Triple("rc", "CTR", 0xFF16A34A.toInt()))
        items.forEachIndexed { i, (id, l, col) ->
            val on = (id == "lib" && apps.visible) || (id == "kb" && kb.visible) || (id == "cfg" && cfg.visible)
            icon(c, w, id, l, col, RectF(190f + i * 98f, 14f, 270f + i * 98f, 94f), 28f, on, iconBmps[id.removePrefix("app:")])
        }
        w.dirty = true
    }

    private fun drawAddr() {
        val w = addr; val b = ensure(w); b.eraseColor(0); val c = Canvas(b); w.btns.clear()
        p.style = Paint.Style.FILL; p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1760f, 112f, 56f, 56f, p)
        key(c, w, "back", "<", RectF(20f, 16f, 110f, 96f), 34f)
        p.style = Paint.Style.FILL; p.color = 0xFF2A3050.toInt(); c.drawRoundRect(130f, 16f, 1560f, 96f, 40f, 40f, p)
        w.btns.add(Btn("kb", RectF(130f, 16f, 1560f, 96f)))
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 34f; tp.color = if (text.isEmpty()) 0xFF9AA3BD.toInt() else Color.WHITE
        c.drawText(if (text.isEmpty()) "Toque aqui para digitar um endereco ou busca" else text.takeLast(60), 160f, 68f, tp)
        key(c, w, "go", "Ir", RectF(1580f, 16f, 1740f, 96f), 34f)
        w.dirty = true
    }

    private fun drawApps() {
        val w = apps; val b = ensure(w); b.eraseColor(0); val c = Canvas(b); w.btns.clear()
        p.style = Paint.Style.FILL; p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1040f, 720f, 40f, 40f, p)
        appList.forEachIndexed { i, a ->
            val x = 45f + (i % 3) * 330f; val y = 50f + (i / 3) * 220f
            val r = RectF(x, y, x + 300f, y + 190f)
            val ib = iconBmps[a.id]; p.color = if (ib != null) 0xFF232840.toInt() else a.color; c.drawRoundRect(r, 28f, 28f, p)
            if (hoverKey == w.id + ":app:" + a.id) { p.color = 0x55FFFFFF; c.drawRoundRect(r, 28f, 28f, p) }
            tp.textAlign = Paint.Align.CENTER; tp.color = Color.WHITE
            if (ib != null) c.drawBitmap(ib, null, RectF(r.centerX() - 60f, r.top + 18f, r.centerX() + 60f, r.top + 138f), ip) else { tp.textSize = 64f; c.drawText(a.glyph, r.centerX(), r.centerY() + 10f, tp) }
            tp.textSize = 30f; c.drawText(a.name, r.centerX(), r.bottom - 26f, tp)
            w.btns.add(Btn("app:" + a.id, r))
        }
        w.dirty = true
    }

    private fun drawKb() {
        val w = kb; val b = ensure(w); b.eraseColor(0); val c = Canvas(b); w.btns.clear()
        p.style = Paint.Style.FILL; p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1280f, 520f, 40f, 40f, p)
        listOf("1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm./").forEachIndexed { j, row ->
            row.forEachIndexed { i, ch -> key(c, w, "k:$ch", "$ch", RectF(40f + i * 120f, 20f + j * 96f, 152f + i * 120f, 104f + j * 96f)) }
        }
        key(c, w, "k:sp", "Espaco", RectF(40f, 404f, 480f, 488f))
        key(c, w, "k:del", "Apagar", RectF(500f, 404f, 760f, 488f))
        key(c, w, "k:go", "Ir", RectF(780f, 404f, 1040f, 488f))
        w.dirty = true
    }

    private fun drawCfg() {
        val w = cfg; val b = ensure(w); b.eraseColor(0); val c = Canvas(b); w.btns.clear()
        p.style = Paint.Style.FILL; p.color = 0xE61A1F30.toInt(); c.drawRoundRect(0f, 0f, 1000f, 800f, 40f, 40f, p)
        val rows = listOf(
            Triple("Lente k1", String.format(Locale.US, "%.2f", k1), "k1"),
            Triple("Lente k2", String.format(Locale.US, "%.2f", k2), "k2"),
            Triple("Olhos (IPD)", "${(ipd * 1000).roundToInt()} mm", "ipd"))
        rows.forEachIndexed { i, (l, v, id) ->
            val y = 30f + i * 100f
            tp.textAlign = Paint.Align.LEFT; tp.textSize = 34f; tp.color = Color.WHITE; c.drawText(l, 40f, y + 62f, tp)
            tp.textAlign = Paint.Align.CENTER; c.drawText(v, 540f, y + 62f, tp)
            key(c, w, "$id-", "-", RectF(640f, y, 740f, y + 84f)); key(c, w, "$id+", "+", RectF(770f, y, 870f, y + 84f))
        }
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 34f; tp.color = Color.WHITE; c.drawText("Andar (virtual)", 40f, 372f, tp)
        listOf("fw" to "Frente", "bk" to "Tras", "lf" to "Esq", "rt" to "Dir").forEachIndexed { i, (id, l) ->
            key(c, w, id, l, RectF(40f + i * 230f, 400f, 250f + i * 230f, 484f), 30f)
        }
        key(c, w, "rc", "Recentrar", RectF(40f, 540f, 340f, 624f), 30f)
        key(c, w, "mode", if (touchMode) "Modo: Toque" else "Modo: Laser", RectF(360f, 540f, 660f, 624f), 28f)
        key(c, w, "exit", "Sair", RectF(680f, 540f, 960f, 624f), 30f)
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 26f; tp.color = 0xFF9AA3BD.toInt()
        c.drawText("As janelas ficam fixas no mundo virtual.", 40f, 700f, tp)
        c.drawText("Sem tracking de posicao real, use Andar.", 40f, 740f, tp)
        w.dirty = true
    }

    // ---------- acoes ----------
    private fun load(u: String) { browserWin.visible = true; post { browser.load(u) } }
    private fun go() {
        val t = text.trim(); if (t.isEmpty()) return
        val u = when {
            t.startsWith("http") -> t
            t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        load(u); kb.visible = false
    }
    private fun onButton(id: String) {
        when {
            id == "back" -> post { browser.back() }
            id == "lib" -> toggle(apps)
            id == "go" -> go()
            id.startsWith("app:") -> appList.firstOrNull { it.id == id.substring(4) }?.let { load(it.url) }
            id == "kb" -> toggle(kb)
            id == "cfg" -> toggle(cfg)
            id == "rc" -> needCenter = true
            id == "mode" -> touchMode = !touchMode
            id == "exit" -> post { onExit() }
            id == "k1-" -> k1 = (k1 - .03f).coerceAtLeast(0f)
            id == "k1+" -> k1 = (k1 + .03f).coerceAtMost(.6f)
            id == "k2-" -> k2 = (k2 - .03f).coerceAtLeast(0f)
            id == "k2+" -> k2 = (k2 + .03f).coerceAtMost(.4f)
            id == "ipd-" -> ipd = (ipd - .001f).coerceAtLeast(.05f)
            id == "ipd+" -> ipd = (ipd + .001f).coerceAtMost(.075f)
            id == "fw" -> walk(1f, 0f)
            id == "bk" -> walk(-1f, 0f)
            id == "lf" -> walk(0f, -1f)
            id == "rt" -> walk(0f, 1f)
            id == "k:del" -> text = text.dropLast(1)
            id == "k:sp" -> text += " "
            id == "k:go" -> go()
            id.startsWith("k:") -> text += id.substring(2)
        }
        uiDirty = true
    }

    private fun sendTouch(action: Int, x: Float, y: Float) { post { browser.touch(action, x, y) } }

    // ---------- interacao: raio (mao ou olhar) contra janelas ----------
    private class Hit(val win: Win, val title: Boolean, val bx: Float, val by: Float, val t: Float, val lx: Float, val ly: Float)
    private class Ptr(val hand: Hand, val ox: Float, val oy: Float, val tx: Float, val ty: Float, val pinch: Boolean)

    private val ptrs = ArrayList<Ptr>()
    private val sx = FloatArray(2); private val sy = FloatArray(2); private val sOn = BooleanArray(2)
    private var touchMode = true                                   // true = toque com o dedo; false = laser + pinca
    private val palmBase = FloatArray(2); private val palmNow = FloatArray(2); private val pushOn = BooleanArray(2)
    private var cropL = 0f; private var cropT = 0f; private var cropS = 1f
    private var cu0 = 0f; private var cv0 = 0f; private var cus = 1f; private var cvs = 1f

    private var mode = 0                    // 0 nada, 1 toque no navegador, 2 mover janela, 3 redimensionar
    private var gw: Win? = null
    private var gDist = 0f; private val gOff = FloatArray(3)
    private var gScale = 1f; private var gLx = 0f
    private var lastBx = 0f; private var lastBy = 0f
    private var pinching = false
    private var dwell = 0f; private var dwellId = ""
    private var rayO = floatArrayOf(0f, 0f, 0f); private var rayD = floatArrayOf(0f, 0f, -1f)
    private var curHit: Hit? = null

    private fun rayFromEye(px: Float, py: Float): FloatArray {
        val ew = fw / 2f; val eh = fh.toFloat(); val asp = ew / eh
        val dx = px / (ew / 2f) * asp; val dy = -py / (eh / 2f)
        return norm(floatArrayOf(R[0] * dx + U[0] * dy + F[0], R[1] * dx + U[1] * dy + F[1], R[2] * dx + U[2] * dy + F[2]))
    }

    private fun planeHit(w: Win, o: FloatArray, d: FloatArray): Hit? {
        val den = dot(d, w.n); if (abs(den) < 1e-4f) return null
        val t = ((w.pos[0] - o[0]) * w.n[0] + (w.pos[1] - o[1]) * w.n[1] + (w.pos[2] - o[2]) * w.n[2]) / den
        if (t < .1f) return null
        val rel = floatArrayOf(o[0] + d[0] * t - w.pos[0], o[1] + d[1] * t - w.pos[1], o[2] + d[2] * t - w.pos[2])
        val lx = dot(rel, w.r); val ly = dot(rel, w.u)
        val ws = w.w * w.scale; val hs = w.h * w.scale
        return Hit(w, false, ((lx / ws + .5f) * w.bw).coerceIn(0f, w.bw.toFloat()), ((.5f - ly / hs) * w.bh).coerceIn(0f, w.bh.toFloat()), t, lx, ly)
    }

    private fun pick(o: FloatArray, d: FloatArray): Hit? {
        var best: Hit? = null
        for (w in wins) {
            if (!w.visible) continue
            val ph = planeHit(w, o, d) ?: continue
            val b = best
            if (b != null && ph.t >= b.t) continue
            val ws = w.w * w.scale; val hs = w.h * w.scale; val th = w.thM
            if (abs(ph.lx) > ws / 2) continue
            if (abs(ph.ly) <= hs / 2) best = ph
            else if (w.hasTitle && ph.ly < -hs / 2 && ph.ly >= -hs / 2 - th)
                best = Hit(w, true, (ph.lx / ws + .5f) * w.tbw, ((-hs / 2 - ph.ly) / th) * w.tbh, ph.t, ph.lx, ph.ly)
        }
        return best
    }

    private fun btnAt(h: Hit): String? = (if (h.title) h.win.tbBtns else h.win.btns).firstOrNull { it.r.contains(h.bx, h.by) }?.id

    private fun activate(h: Hit, allowTouch: Boolean) {
        val id = btnAt(h)
        if (h.title) { if (id == "close" || id == "min") h.win.visible = false }
        else if (h.win === browserWin) { if (allowTouch) { sendTouch(MotionEvent.ACTION_DOWN, h.bx, h.by); sendTouch(MotionEvent.ACTION_UP, h.bx, h.by) } }
        else if (id != null) onButton(id)
    }

    private fun interact(dt: Float) {
        // ponteiros das maos (em pixels do olho, antes da lente)
        ptrs.clear()
        val cb = cam; val hs = hands
        val ew = fw / 2f; val eh = fh.toFloat()
        val seen = BooleanArray(2)
        if (cb != null) for (hd in hs) {
            val k = if (hd.left) 0 else 1
            fun X(i: Int) = (hd.pts[2 * i] * cb.width - cropL) * cropS - ew / 2f
            fun Y(i: Int) = (hd.pts[2 * i + 1] * cb.height - cropT) * cropS - eh / 2f
            val ox = (X(4) + X(8)) / 2f; val oy = (Y(4) + Y(8)) / 2f
            var dx = X(5) - X(0); var dy = Y(5) - Y(0)             // punho -> base do indicador
            val n = hypot(dx, dy).coerceAtLeast(1f); dx /= n; dy /= n
            val tipX = X(8); val tipY = Y(8)                          // ponta do indicador
            val palm = hypot(X(5) - X(17), Y(5) - Y(17)).coerceAtLeast(5f)  // largura da mao (cresce ao aproximar da camera)
            if (!sOn[k]) { palmBase[k] = palm; palmNow[k] = palm; pushOn[k] = false }
            else { palmNow[k] += (palm - palmNow[k]) * .5f; palmBase[k] += (palmNow[k] - palmBase[k]) * .04f }
            val ratio = palmNow[k] / palmBase[k]
            pushOn[k] = if (pushOn[k]) ratio > 1.07f else ratio > 1.16f
            val tx0 = if (touchMode) tipX else ox + dx * ew * .35f
            val ty0 = if (touchMode) tipY else oy + dy * ew * .35f
            val sm = if (touchMode) .55f else .3f
            if (!sOn[k]) { sx[k] = tx0; sy[k] = ty0 } else { sx[k] += (tx0 - sx[k]) * sm; sy[k] += (ty0 - sy[k]) * sm }
            sOn[k] = true; seen[k] = true
            val press = hd.pinch || (touchMode && pushOn[k])
            ptrs.add(Ptr(hd, if (touchMode) tipX else ox, if (touchMode) tipY else oy, sx[k], sy[k], press))
        }
        for (k in 0..1) if (!seen[k]) sOn[k] = false
        val act = ptrs.firstOrNull { it.pinch } ?: ptrs.firstOrNull()

        val o = floatArrayOf(head[0], head[1], head[2])
        val d = if (act != null) rayFromEye(act.tx, act.ty) else F
        rayO = o; rayD = d

        val h = if (mode == 0) pick(o, d) else gw?.let { planeHit(it, o, d) }
        curHit = h
        val id = if (mode == 0 && h != null) btnAt(h) else null
        val key = if (mode == 0 && h != null) h.win.id + ":" + (id ?: "") else ""
        if (key != hoverKey) { hoverKey = key; uiDirty = true }

        if (tap) { tap = false; if (h != null && mode == 0) activate(h, true) }

        val pinch = act?.pinch == true
        if (pinch && !pinching) {
            pinching = true; dwell = -.8f
            if (h != null) {
                if (h.title) {
                    when (id) {
                        "close", "min" -> h.win.visible = false
                        "size" -> { mode = 3; gw = h.win; gScale = h.win.scale; gLx = h.lx }
                        else -> {
                            mode = 2; gw = h.win; gDist = h.t
                            for (i in 0..2) gOff[i] = h.win.pos[i] - (o[i] + d[i] * h.t)
                        }
                    }
                } else if (h.win === browserWin) {
                    mode = 1; gw = h.win; lastBx = h.bx; lastBy = h.by
                    sendTouch(MotionEvent.ACTION_DOWN, h.bx, h.by)
                } else if (id != null) onButton(id)
            }
        } else if (pinch) {
            val w = gw
            if (w != null && h != null) when (mode) {
                1 -> { lastBx = h.bx; lastBy = h.by; sendTouch(MotionEvent.ACTION_MOVE, h.bx, h.by) }
                2 -> place(w, floatArrayOf(o[0] + d[0] * gDist + gOff[0], o[1] + d[1] * gDist + gOff[1], o[2] + d[2] * gDist + gOff[2]))
                3 -> { w.scale = (gScale * (1f + (h.lx - gLx) / (w.w * gScale))).coerceIn(.4f, 3f); uiDirty = true }
            }
        } else if (pinching) {
            pinching = false
            if (mode == 1) sendTouch(MotionEvent.ACTION_UP, lastBx, lastBy)
            mode = 0; gw = null
        }

        if (!pinching && ptrs.isEmpty()) {          // sem maos: olhar fixo em botao = clique
            if (id != null && id == dwellId) dwell += dt else dwell = 0f
            dwellId = id ?: ""
            if (dwell >= 1.2f && h != null) { activate(h, false); dwell = -.8f }
        } else if (ptrs.isNotEmpty()) dwell = 0f
    }

    // Toque na tela (se o VR Box tiver botao) = clique no ponto do olhar
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) tap = true
        return true
    }

    // ---------- overlay 2D (maos, laser, diagnostico) ----------
    private var ovBmp: Bitmap? = null
    private var ovTex = 0
    private val chains = arrayOf(intArrayOf(0, 1, 2, 3, 4), intArrayOf(0, 5, 6, 7, 8), intArrayOf(0, 9, 10, 11, 12),
        intArrayOf(0, 13, 14, 15, 16), intArrayOf(0, 17, 18, 19, 20))

    private fun drawOverlay() {
        val b = ovBmp ?: return
        b.eraseColor(Color.TRANSPARENT)

        val c = Canvas(b)
        val ew = fw / 2f
        val eh = fh.toFloat()

        // O bitmap do overlay tem exatamente a dimensao de UM olho.
        // Assim, as coordenadas calculadas pelo tracking nao sofrem deslocamento.
        c.translate(b.width / 2f, b.height / 2f)
        c.scale(b.width / ew, b.height / eh)

        val cb = cam
        if (cb == null) {
            hp.shader = null
            hp.style = Paint.Style.FILL
            return
        }

        // Diagnostico discreto, mantido do sistema original.
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 26f
        tp.color = Color.WHITE
        c.drawText(
            "camera: ok   maos: ${hands.size}" +
                (if (touchMode) "   toque" else "   laser") +
                (if (pinching) "   CLIQUE" else ""),
            0f,
            -eh / 2f + 60f,
            tp
        )

        for (q in ptrs) {
            // Converte os landmarks da camera para coordenadas do olho.
            fun X(i: Int): Float =
                (q.hand.pts[2 * i] * cb.width - cropL) * cropS - ew / 2f

            fun Y(i: Int): Float =
                (q.hand.pts[2 * i + 1] * cb.height - cropT) * cropS - eh / 2f

            val palm = hypot(X(0) - X(9), Y(0) - Y(9)).coerceAtLeast(20f)
            val outlineWidth = (palm * 0.22f).coerceIn(5f, 30f)
            val fillWidth = (palm * 0.135f).coerceIn(3f, 20f)
            val gray = 0xFFD0D0D0.toInt()

            hp.shader = null
            hp.alpha = 255
            hp.strokeCap = Paint.Cap.ROUND
            hp.strokeJoin = Paint.Join.ROUND

            // Laser fica atras da mao, para nao atravessar visualmente os dedos.
            hp.style = Paint.Style.STROKE
            hp.shader = LinearGradient(
                q.ox, q.oy, q.tx + 0.5f, q.ty + 0.5f,
                if (q.pinch) 0xFF22C55E.toInt() else 0xCCFFFFFF.toInt(),
                0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
            hp.strokeWidth = max(4f, palm * 0.045f)
            c.drawLine(q.ox, q.oy, q.tx, q.ty, hp)
            hp.shader = null

            // Contorno externo da palma.
            val palmPath = Path().apply {
                moveTo(X(0), Y(0))
                for (i in intArrayOf(1, 5, 9, 13, 17)) {
                    lineTo(X(i), Y(i))
                }
                close()
            }

            hp.style = Paint.Style.FILL
            hp.color = gray
            c.drawPath(palmPath, hp)

            hp.style = Paint.Style.STROKE
            hp.color = Color.BLACK
            hp.strokeWidth = outlineWidth
            c.drawPath(palmPath, hp)

            // Primeiro desenha o contorno preto de cada dedo.
            hp.style = Paint.Style.STROKE
            hp.color = Color.BLACK
            hp.strokeWidth = outlineWidth
            for (chain in chains) {
                val path = Path().apply {
                    moveTo(X(chain[0]), Y(chain[0]))
                    for (k in 1 until chain.size) {
                        lineTo(X(chain[k]), Y(chain[k]))
                    }
                }
                c.drawPath(path, hp)
            }

            // Depois desenha o preenchimento cinza por cima do contorno.
            hp.color = gray
            hp.strokeWidth = fillWidth
            for (chain in chains) {
                val path = Path().apply {
                    moveTo(X(chain[0]), Y(chain[0]))
                    for (k in 1 until chain.size) {
                        lineTo(X(chain[k]), Y(chain[k]))
                    }
                }
                c.drawPath(path, hp)
            }

            // Articulacoes com contorno preto e centro cinza.
            hp.style = Paint.Style.FILL
            for (i in 0..20) {
                val radius = (palm * 0.052f).coerceIn(3f, 12f)
                hp.color = Color.BLACK
                c.drawCircle(X(i), Y(i), radius, hp)
                hp.color = gray
                c.drawCircle(X(i), Y(i), radius * 0.66f, hp)
            }

            // Ponta do indicador: destaque visual alinhado ao landmark 8.
            val tipRadius = (palm * 0.09f).coerceIn(5f, 18f)
            hp.color = Color.BLACK
            c.drawCircle(X(8), Y(8), tipRadius, hp)
            hp.color = if (q.pinch) 0xFF22C55E.toInt() else gray
            c.drawCircle(X(8), Y(8), tipRadius * 0.68f, hp)
        }

        hp.shader = null
        hp.alpha = 255
        hp.style = Paint.Style.FILL
    }

    // ---------- OpenGL ----------
    private var progQ = 0; private var progD = 0
    private lateinit var quad: FloatBuffer
    private var qPos = 0; private var qMvp = 0; private var qUv = 0; private var qA = 0; private var qTex = 0
    private var dPos = 0; private var dK1 = 0; private var dK2 = 0; private var dZ = 0; private var dTex = 0
    private var sw = 1; private var sh = 1; private var fw = 2; private var fh = 2
    private var fbo = 0; private var fboTex = 0
    private var camTex = 0; private var lastCam: Bitmap? = null
    private var lastVer = -1
    private var lastT = System.nanoTime()
    private var curBmp: Bitmap? = null; private var curTex = 0; private var curKey = -1
    private val FULL = floatArrayOf(2f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
        loadIcons()
        for (w in wins) spawn(w)
        kb.visible = false; cfg.visible = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progQ = GlUtil.program(VS_Q, FS_Q); progD = GlUtil.program(VS_D, FS_D)
        qPos = GLES20.glGetAttribLocation(progQ, "aPos"); qMvp = GLES20.glGetUniformLocation(progQ, "uMVP")
        qUv = GLES20.glGetUniformLocation(progQ, "uUV"); qA = GLES20.glGetUniformLocation(progQ, "uA"); qTex = GLES20.glGetUniformLocation(progQ, "uTex")
        dPos = GLES20.glGetAttribLocation(progD, "aPos"); dK1 = GLES20.glGetUniformLocation(progD, "uK1")
        dK2 = GLES20.glGetUniformLocation(progD, "uK2"); dZ = GLES20.glGetUniformLocation(progD, "uZ"); dTex = GLES20.glGetUniformLocation(progD, "uTex")
        quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-.5f, -.5f, .5f, -.5f, -.5f, .5f, .5f, .5f)); quad.position(0)
        for (w in wins) { w.tex = 0; w.tTex = 0; w.dirty = true; w.tDirty = true }
        camTex = 0; lastCam = null; ovTex = 0; curTex = 0; curKey = -1; lastVer = -1; fbo = 0
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        uiDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        sw = w; sh = h
        fw = ((w * .85f).toInt() / 2) * 2; fh = (h * .85f).toInt()
        fboTex = GlUtil.tex()
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        ovBmp = Bitmap.createBitmap(max(fw / 2, 64), max(fh, 64), Bitmap.Config.ARGB_8888)
    }

    private fun up(tex: Int, b: Bitmap): Int {
        val t = if (tex == 0) GlUtil.tex() else tex.also { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it) }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        return t
    }

    private fun drawQuad(mvp: FloatArray, tex: Int, u0: Float, v0: Float, us: Float, vs: Float, a: Float) {
        GLES20.glUseProgram(progQ)
        GLES20.glEnableVertexAttribArray(qPos)
        GLES20.glVertexAttribPointer(qPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniformMatrix4fv(qMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(qUv, u0, v0, us, vs)
        GLES20.glUniform1f(qA, a)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(qTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun fillModel(m: FloatArray, w: Win, sx: Float, sy: Float, cy: Float) {
        for (i in 0..2) { m[i] = w.r[i] * sx; m[4 + i] = w.u[i] * sy; m[8 + i] = w.n[i]; m[12 + i] = w.pos[i] + w.u[i] * cy }
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
    }

    private fun drawWin(w: Win, vp: FloatArray) {
        val ws = w.w * w.scale; val hs = w.h * w.scale
        val m = FloatArray(16); val mvp = FloatArray(16)
        fillModel(m, w, ws, hs, 0f); GLM.multiplyMM(mvp, 0, vp, 0, m, 0)
        if (w.tex != 0) drawQuad(mvp, w.tex, 0f, 0f, w.uScale, 1f, 1f)
        if (w.hasTitle && w.tTex != 0) {
            fillModel(m, w, ws, w.thM, -(hs / 2f + w.thM / 2f)); GLM.multiplyMM(mvp, 0, vp, 0, m, 0)
            drawQuad(mvp, w.tTex, 0f, 0f, 1f, 1f, 1f)
        }
    }

    private fun drawCursor(vp: FloatArray) {
        val h = curHit
        val pos: FloatArray; val r: FloatArray; val u: FloatArray; val n: FloatArray; val dist: Float
        if (h != null) {
            val w = h.win
            pos = floatArrayOf(rayO[0] + rayD[0] * h.t + w.n[0] * .01f, rayO[1] + rayD[1] * h.t + w.n[1] * .01f, rayO[2] + rayD[2] * h.t + w.n[2] * .01f)
            r = w.r; u = w.u; n = w.n; dist = h.t
        } else {
            pos = floatArrayOf(rayO[0] + rayD[0] * 2.5f, rayO[1] + rayD[1] * 2.5f, rayO[2] + rayD[2] * 2.5f)
            r = R; u = U; n = floatArrayOf(-F[0], -F[1], -F[2]); dist = 2.5f
        }
        val key = (if (pinching) 1000 else 0) + (max(dwell, 0f) * 20f).toInt()
        if (key != curKey || curTex == 0) {
            curKey = key
            val b = curBmp ?: Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { curBmp = it }
            b.eraseColor(0); val c = Canvas(b)
            p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.color = if (pinching) 0xFF22C55E.toInt() else Color.WHITE
            c.drawCircle(48f, 48f, 26f, p)
            if (dwell > 0f) { p.color = 0xFF9D86FF.toInt(); c.drawArc(RectF(10f, 10f, 86f, 86f), -90f, 360f * dwell / 1.2f, false, p) }
            p.style = Paint.Style.FILL; p.color = if (pinching) 0xFF22C55E.toInt() else Color.WHITE; c.drawCircle(48f, 48f, 6f, p)
            curTex = up(curTex, b)
        }
        val s = (.06f * dist / 2f).coerceIn(.04f, .14f)
        val m = FloatArray(16); val mvp = FloatArray(16)
        for (i in 0..2) { m[i] = r[i] * s; m[4 + i] = u[i] * s; m[8 + i] = n[i]; m[12 + i] = pos[i] }
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        GLM.multiplyMM(mvp, 0, vp, 0, m, 0)
        drawQuad(mvp, curTex, 0f, 0f, 1f, 1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime(); val dt = ((now - lastT) / 1e9f).coerceAtMost(.1f); lastT = now
        updateHead()

        for (i in 0..2) head[i] = walkOff[i]

        val bf = browser.poll()
        if (bf != null && browser.version != lastVer) {
            lastVer = browser.version; browserWin.bmp = bf
            browserWin.uScale = browser.w.toFloat() / bf.width; browserWin.dirty = true
        }
        val cb = cam
        val ew = fw / 2f; val eh = fh.toFloat()
        if (cb != null) {
            cropS = max(ew / cb.width, eh / cb.height)
            val srcW = ew / cropS; val srcH = eh / cropS
            cropL = (cb.width - srcW) / 2f; cropT = (cb.height - srcH) / 2f
            cu0 = cropL / cb.width; cv0 = cropT / cb.height; cus = srcW / cb.width; cvs = srcH / cb.height
            if (cb !== lastCam) { camTex = up(camTex, cb); lastCam = cb }
        }

        interact(dt)
        if (uiDirty) { uiDirty = false; drawDock(); drawAddr(); drawApps(); drawKb(); drawCfg(); for (w in wins) w.tDirty = true }
        drawOverlay()
        ovBmp?.let { ovTex = up(ovTex, it) }
        for (w in wins) {
            if (!w.visible) continue
            if (w.hasTitle && w.tDirty) { drawTitle(w); w.tBmp?.let { w.tTex = up(w.tTex, it) } }
            if (w.dirty) { w.bmp?.let { w.tex = up(w.tex, it) }; w.dirty = false }
        }
        render()
    }

    private fun render() {
        val ew = fw / 2
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, fw, fh)
        GLES20.glClearColor(.02f, .03f, .07f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val view = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        GLM.perspectiveM(proj, 0, 90f, ew.toFloat() / fh, .05f, 60f)
        for (eye in 0..1) {
            GLES20.glViewport(eye * ew, 0, ew, fh)
            if (cam != null && camTex != 0) drawQuad(FULL, camTex, cu0, cv0, cus, cvs, 1f)
            val s = if (eye == 0) -ipd / 2f else ipd / 2f
            val ex = head[0] + R[0] * s; val ey = head[1] + R[1] * s; val ez = head[2] + R[2] * s
            GLM.setLookAtM(view, 0, ex, ey, ez, ex + F[0], ey + F[1], ez + F[2], U[0], U[1], U[2])
            GLM.multiplyMM(vp, 0, proj, 0, view, 0)
            val vis = wins.filter { it.visible }.sortedByDescending {
                val dx = it.pos[0] - ex; val dy = it.pos[1] - ey; val dz = it.pos[2] - ez; dx * dx + dy * dy + dz * dz
            }
            // Camadas 3D primeiro: as janelas e o cursor ficam atras da mao.
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            for (w in vis) drawWin(w, vp)
            drawCursor(vp)

            // A mao vem por ultimo em coordenadas do olho (overlay transparente).
            // Isso garante que ela seja composta sobre a superficie das janelas.
            if (ovTex != 0) {
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawQuad(FULL, ovTex, 0f, 0f, 1f, 1f, 1f)
            }
        }
        // passada final: correcao de distorcao das lentes
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(progD)
        GLES20.glEnableVertexAttribArray(dPos)
        GLES20.glVertexAttribPointer(dPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform1f(dK1, k1); GLES20.glUniform1f(dK2, k2); GLES20.glUniform1f(dZ, 1f / (1f + k1 + k2))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(dTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
