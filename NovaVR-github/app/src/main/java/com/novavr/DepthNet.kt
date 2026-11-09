package com.novavr

import android.app.Activity
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Profundidade por IA no proprio aparelho (MiDaS v2.1 small, 256x256), sem servicos externos.
 * O modelo (assets/midas.tflite) e baixado pelo GitHub Actions na hora de compilar. Se nao existir, o app
 * funciona normalmente, so sem a oclusao.
 *
 * A saida do MiDaS e profundidade INVERSA RELATIVA (sem escala em metros). Aqui ela e normalizada por
 * percentis e mapeada para uma faixa de 0,6 a 6 m. E uma estimativa grosseira, boa para dizer
 * "isto esta bem mais perto que a janela", nao para medir.
 */
class DepthNet(private val act: Activity) {
    companion object {
        const val IN = 256
        const val OW = 64
        const val OH = 64
    }

    @Volatile var enabled = false
    @Volatile var status = "carregando..."
    @Volatile var depth: ByteArray? = null      // OW*OH; 0..255 = 0..8 m
    @Volatile var version = 0

    private var interp: Interpreter? = null
    private var inBuf: ByteBuffer? = null
    private var outCount = 0
    private val ex = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var lastT = 0L
    private var smooth: FloatArray? = null

    /** Chamar fora da thread principal (carrega ~30 MB). */
    fun init() {
        try {
            val bytes = act.assets.open("midas.tflite").use { it.readBytes() }
            val mm = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            mm.put(bytes)
            mm.rewind()
            val opt = Interpreter.Options()
            opt.setNumThreads(4)
            val ip = Interpreter(mm, opt)
            val ish = ip.getInputTensor(0).shape()
            if (ish.size != 4 || ish[1] != IN || ish[2] != IN || ish[3] != 3) { status = "modelo incompativel"; return }
            val osh = ip.getOutputTensor(0).shape()
            var n = 1
            for (d in osh) n *= d
            outCount = n
            val side = sqrt(n.toDouble()).toInt()
            if (side * side != n || side % OW != 0) { status = "modelo incompativel"; return }
            inBuf = ByteBuffer.allocateDirect(4 * IN * IN * 3).order(ByteOrder.nativeOrder())
            interp = ip
            status = "modelo ok"
        } catch (e: Throwable) {
            status = "sem modelo (opcional)"
        }
    }

    /** Chamar com os quadros da camera; roda em segundo plano, ~3 vezes por segundo. */
    fun feed(b: Bitmap, nowMs: Long) {
        val ip = interp ?: return
        if (!enabled) return
        if (nowMs - lastT < 350) return
        if (!busy.compareAndSet(false, true)) return
        lastT = nowMs
        val px = IntArray(IN * IN)
        try {
            Bitmap.createScaledBitmap(b, IN, IN, true).getPixels(px, 0, IN, 0, 0, IN, IN)
        } catch (e: Exception) {
            busy.set(false)
            return
        }
        ex.execute {
            try { infer(ip, px) } catch (e: Throwable) { status = "erro na IA" } finally { busy.set(false) }
        }
    }

    private fun infer(ip: Interpreter, px: IntArray) {
        val ib = inBuf ?: return
        ib.rewind()
        for (i in 0 until IN * IN) {
            val c = px[i]
            ib.putFloat((((c shr 16) and 255) / 255f - 0.485f) / 0.229f)
            ib.putFloat((((c shr 8) and 255) / 255f - 0.456f) / 0.224f)
            ib.putFloat(((c and 255) / 255f - 0.406f) / 0.225f)
        }
        ib.rewind()
        val ob = ByteBuffer.allocateDirect(4 * outCount).order(ByteOrder.nativeOrder())
        ip.run(ib, ob)
        ob.rewind()
        val v = FloatArray(outCount)
        ob.asFloatBuffer().get(v)
        // percentis 5% e 95% por amostragem
        val sample = FloatArray(1024) { v[(it * (outCount / 1024))] }
        sample.sort()
        val lo = sample[51]
        val hi = sample[972]
        val span = max(hi - lo, 1e-3f)
        val side = sqrt(outCount.toDouble()).toInt()
        val bw = side / OW
        val res = FloatArray(OW * OH)
        for (oy in 0 until OH) {
            for (ox in 0 until OW) {
                var s = 0f
                for (dy in 0 until bw) {
                    for (dx in 0 until bw) s += v[(oy * bw + dy) * side + ox * bw + dx]
                }
                val nr = min(1f, max(0f, (s / (bw * bw) - lo) / span))        // 1 = perto
                val inv = 1f / 6f + nr * (1f / 0.6f - 1f / 6f)               // profundidade inversa entre 1/6 e 1/0.6
                res[oy * OW + ox] = 1f / inv                                  // metros (0,6 a 6)
            }
        }
        val sm = smooth
        if (sm == null) smooth = res else for (i in res.indices) sm[i] += (res[i] - sm[i]) * 0.5f
        val use = smooth ?: res
        val out = ByteArray(OW * OH)
        for (i in out.indices) out[i] = (min(1f, use[i] / 8f) * 255f).toInt().toByte()
        depth = out
        version++
    }
}
