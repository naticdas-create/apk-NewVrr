package com.novavr

import android.graphics.Bitmap
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Odometria visual com giroscopio (sem ARCore, sem servicos externos).
 *
 * A rotacao da cabeca vem do giroscopio (preciso e rapido). A camera so precisa dizer PARA ONDE voce esta
 * se movendo: pontos da cena sao seguidos entre dois quadros (Lucas-Kanade, com a posicao prevista pela
 * rotacao do giroscopio) e a direcao do movimento sai da geometria epipolar com rotacao conhecida
 * (RANSAC + minimos quadrados). O sentido (frente/tras) vem de exigir que os pontos fiquem na frente da camera.
 * A distancia (escala) vem dos passos (StepWalker).
 */
class Vio(fovXdeg: Float = 65f) {
    companion object {
        const val W = 160
        const val H = 120
        private const val CELLW = 16
        private const val CELLH = 15
        private const val RAD = 4
    }

    private val f: Float = ((W / 2f) / tan(Math.toRadians(fovXdeg.toDouble() / 2.0))).toFloat()
    private val cx = W / 2f
    private val cy = H / 2f

    @Volatile var enabled = true
    @Volatile var dirX = 0f          // direcao do movimento no mundo (plano horizontal), unitaria
    @Volatile var dirZ = -1f
    @Volatile var conf = 0f          // 0..1
    @Volatile var parallax = 0f      // px (mediana do deslocamento causado pelo movimento)
    @Volatile var inliers = 0

    private val ex = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var lastT = 0L
    private val rnd = Random(7)

    // quadro anterior (somente na thread do executor)
    private var pG: FloatArray? = null
    private var pB: FloatArray? = null

    private val tpl = FloatArray((2 * RAD + 1) * (2 * RAD + 1))
    private val gxs = FloatArray(tpl.size)
    private val gys = FloatArray(tpl.size)
    private val cur = FloatArray(tpl.size)

    /** Chamar com cada quadro novo da camera. R, U, F = eixos da cabeca no mundo (direita, cima, frente). */
    fun feed(b: Bitmap, nowMs: Long, R: FloatArray, U: FloatArray, F: FloatArray) {
        if (!enabled) return
        if (nowMs - lastT < 90) return
        if (!busy.compareAndSet(false, true)) return
        lastT = nowMs
        val g = FloatArray(W * H)
        try {
            val small = Bitmap.createScaledBitmap(b, W, H, true)
            val px = IntArray(W * H)
            small.getPixels(px, 0, W, 0, 0, W, H)
            for (i in 0 until W * H) {
                val c = px[i]
                g[i] = ((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f
            }
        } catch (e: Exception) {
            busy.set(false)
            return
        }
        // colunas = eixos da camera no mundo: x = direita, y = cima, z = tras (-frente)
        val basis = floatArrayOf(R[0], R[1], R[2], U[0], U[1], U[2], -F[0], -F[1], -F[2])
        ex.execute {
            try { work(g, basis) } catch (e: Exception) { } finally { busy.set(false) }
        }
    }

    private fun bil(g: FloatArray, x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i = y0 * W + x0
        return (g[i] * (1f - fx) + g[i + 1] * fx) * (1f - fy) + (g[i + W] * (1f - fx) + g[i + W + 1] * fx) * fy
    }

    /** Cantos (Shi-Tomasi), um por celula da grade. Devolve x,y,x,y,... */
    private fun detect(g: FloatArray): IntArray {
        val ix = FloatArray(W * H)
        val iy = FloatArray(W * H)
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                val i = y * W + x
                ix[i] = (g[i + 1] - g[i - 1]) * 0.5f
                iy[i] = (g[i + W] - g[i - W]) * 0.5f
            }
        }
        val lam = FloatArray(W * H)
        for (y in 3 until H - 3) {
            for (x in 3 until W - 3) {
                var sxx = 0f; var sxy = 0f; var syy = 0f
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val j = (y + dy) * W + x + dx
                        val a = ix[j]; val b = iy[j]
                        sxx += a * a; sxy += a * b; syy += b * b
                    }
                }
                lam[y * W + x] = 0.5f * (sxx + syy - sqrt((sxx - syy) * (sxx - syy) + 4f * sxy * sxy))
            }
        }
        val out = ArrayList<Int>()
        var cyy = 0
        while (cyy < H) {
            var cxx = 0
            while (cxx < W) {
                var best = -1f; var bx = 0; var by = 0
                for (y in max(cyy, 10) until min(cyy + CELLH, H - 10)) {
                    for (x in max(cxx, 10) until min(cxx + CELLW, W - 10)) {
                        val v = lam[y * W + x]
                        if (v > best) { best = v; bx = x; by = y }
                    }
                }
                if (best > 150f) { out.add(bx); out.add(by) }
                cxx += CELLW
            }
            cyy += CELLH
        }
        return out.toIntArray()
    }

    /** Lucas-Kanade em uma janela 9x9, comecando na posicao prevista (px,py). Devolve a posicao final ou null. */
    private fun track(g1: FloatArray, g2: FloatArray, x: Int, y: Int, px: Float, py: Float): FloatArray? {
        val n = tpl.size
        var k = 0
        for (oy in -RAD..RAD) {
            for (ox in -RAD..RAD) {
                val sx = (x + ox).toFloat(); val sy = (y + oy).toFloat()
                tpl[k] = bil(g1, sx, sy)
                gxs[k] = (bil(g1, sx + 1f, sy) - bil(g1, sx - 1f, sy)) * 0.5f
                gys[k] = (bil(g1, sx, sy + 1f) - bil(g1, sx, sy - 1f)) * 0.5f
                k++
            }
        }
        var g00 = 0f; var g01 = 0f; var g11 = 0f; var mt = 0f
        for (i in 0 until n) { g00 += gxs[i] * gxs[i]; g01 += gxs[i] * gys[i]; g11 += gys[i] * gys[i]; mt += tpl[i] }
        mt /= n
        val det = g00 * g11 - g01 * g01
        if (det < 1e-3f) return null
        var qx = px; var qy = py
        val lo = (RAD + 2).toFloat()
        for (it in 0 until 8) {
            if (qx < lo || qx > W - RAD - 4 || qy < lo || qy > H - RAD - 4) return null
            var mc = 0f
            k = 0
            for (oy in -RAD..RAD) { for (ox in -RAD..RAD) { cur[k] = bil(g2, qx + ox, qy + oy); mc += cur[k]; k++ } }
            mc /= n
            var b0 = 0f; var b1 = 0f
            for (i in 0 until n) {
                val e = (cur[i] - mc) - (tpl[i] - mt)
                b0 += gxs[i] * e; b1 += gys[i] * e
            }
            val dx = -(g11 * b0 - g01 * b1) / det
            val dy = -(-g01 * b0 + g00 * b1) / det
            qx += dx; qy += dy
            if (dx * dx + dy * dy < 0.0009f) break
        }
        if (qx < lo || qx > W - RAD - 4 || qy < lo || qy > H - RAD - 4) return null
        var mc = 0f
        k = 0
        for (oy in -RAD..RAD) { for (ox in -RAD..RAD) { cur[k] = bil(g2, qx + ox, qy + oy); mc += cur[k]; k++ } }
        mc /= n
        var res = 0f
        for (i in 0 until n) { val e = (cur[i] - mc) - (tpl[i] - mt); res += e * e }
        if (res / n > 150f) return null
        if (hypot(qx - px, qy - py) > 6f) return null
        return floatArrayOf(qx, qy)
    }

    /** Autovetor do menor autovalor de uma matriz simetrica 3x3 (Jacobi). */
    private fun eigMin(m: DoubleArray): DoubleArray {
        val a = m.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (sweep in 0 until 15) {
            var p = 0; var q = 1; var mx = abs(a[1])
            if (abs(a[2]) > mx) { p = 0; q = 2; mx = abs(a[2]) }
            if (abs(a[5]) > mx) { p = 1; q = 2; mx = abs(a[5]) }
            if (mx < 1e-12) break
            val app = a[p * 3 + p]; val aqq = a[q * 3 + q]; val apq = a[p * 3 + q]
            val theta = (aqq - app) / (2.0 * apq)
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
            val c = 1.0 / sqrt(t * t + 1.0)
            val s = t * c
            for (k in 0..2) {
                val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
                a[k * 3 + p] = c * akp - s * akq; a[k * 3 + q] = s * akp + c * akq
            }
            for (k in 0..2) {
                val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
                a[p * 3 + k] = c * apk - s * aqk; a[q * 3 + k] = s * apk + c * aqk
            }
            for (k in 0..2) {
                val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]
                v[k * 3 + p] = c * vkp - s * vkq; v[k * 3 + q] = s * vkp + c * vkq
            }
        }
        var mi = 0
        if (a[4] < a[0]) mi = 1
        if (a[8] < a[mi * 3 + mi]) mi = 2
        return doubleArrayOf(v[mi], v[3 + mi], v[6 + mi])
    }

    private fun fail() { conf *= 0.7f; if (conf < 0.05f) conf = 0f; inliers = 0 }

    private fun work(g2: FloatArray, b2: FloatArray) {
        val g1 = pG
        val b1 = pB
        pG = g2; pB = b2
        if (g1 == null || b1 == null) return
        // rotacao relativa camera1 -> camera2 (do giroscopio): R12[i][j] = col_i(B2) . col_j(B1)
        val r12 = FloatArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                r12[i * 3 + j] = b2[i * 3] * b1[j * 3] + b2[i * 3 + 1] * b1[j * 3 + 1] + b2[i * 3 + 2] * b1[j * 3 + 2]
            }
        }
        val feats = detect(g1)
        val nf = feats.size / 2
        val ax = FloatArray(nf); val ay = FloatArray(nf); val az = FloatArray(nf)       // (R12 x1) x x2, sem normalizar
        val x1a = FloatArray(nf * 3); val x2a = FloatArray(nf * 3); val rxa = FloatArray(nf * 3)
        val flow = FloatArray(nf)
        var n = 0
        for (i in 0 until nf) {
            val fx = feats[2 * i]; val fy = feats[2 * i + 1]
            val x1 = (fx - cx) / f; val y1 = -(fy - cy) / f; val z1 = -1f
            val rx = r12[0] * x1 + r12[1] * y1 + r12[2] * z1
            val ry = r12[3] * x1 + r12[4] * y1 + r12[5] * z1
            val rz = r12[6] * x1 + r12[7] * y1 + r12[8] * z1
            if (rz > -0.2f) continue
            val u = cx + f * rx / (-rz)
            val v = cy - f * ry / (-rz)
            val q = track(g1, g2, fx, fy, u, v) ?: continue
            val x2 = (q[0] - cx) / f; val y2 = -(q[1] - cy) / f; val z2 = -1f
            ax[n] = ry * z2 - rz * y2
            ay[n] = rz * x2 - rx * z2
            az[n] = rx * y2 - ry * x2
            x1a[n * 3] = x1; x1a[n * 3 + 1] = y1; x1a[n * 3 + 2] = z1
            x2a[n * 3] = x2; x2a[n * 3 + 1] = y2; x2a[n * 3 + 2] = z2
            rxa[n * 3] = rx; rxa[n * 3 + 1] = ry; rxa[n * 3 + 2] = rz
            flow[n] = hypot(q[0] - u, q[1] - v)
            n++
        }
        if (n < 14) { fail(); return }
        val sorted = flow.copyOf(n); sorted.sort()
        val par = sorted[n / 2]
        parallax = par
        val mag = FloatArray(n) { sqrt(ax[it] * ax[it] + ay[it] * ay[it] + az[it] * az[it]) }
        val thr = 1.8f / f
        val minMag = 1.0f / f
        var bestCount = 0
        var bt0 = 0f; var bt1 = 0f; var bt2 = 0f
        for (iter in 0 until 60) {
            val i = rnd.nextInt(n); var j = rnd.nextInt(n)
            if (i == j) j = (j + 1) % n
            if (mag[i] < 1e-9f || mag[j] < 1e-9f) continue
            val ux = ax[i] / mag[i]; val uy = ay[i] / mag[i]; val uz = az[i] / mag[i]
            val vx = ax[j] / mag[j]; val vy = ay[j] / mag[j]; val vz = az[j] / mag[j]
            var tx = uy * vz - uz * vy; var ty = uz * vx - ux * vz; var tz = ux * vy - uy * vx
            val nt = sqrt(tx * tx + ty * ty + tz * tz)
            if (nt < 0.05f) continue
            tx /= nt; ty /= nt; tz /= nt
            var cnt = 0
            for (k in 0 until n) {
                if (mag[k] > minMag && abs(ax[k] * tx + ay[k] * ty + az[k] * tz) < thr) cnt++
            }
            if (cnt > bestCount) { bestCount = cnt; bt0 = tx; bt1 = ty; bt2 = tz }
        }
        inliers = bestCount
        if (bestCount < 12 || par < 0.7f) { fail(); return }
        // refinamento por minimos quadrados nos inliers
        val m = DoubleArray(9)
        val keep = BooleanArray(n)
        for (k in 0 until n) {
            if (mag[k] > minMag && abs(ax[k] * bt0 + ay[k] * bt1 + az[k] * bt2) < thr) {
                keep[k] = true
                val a0 = ax[k].toDouble(); val a1 = ay[k].toDouble(); val a2 = az[k].toDouble()
                m[0] += a0 * a0; m[1] += a0 * a1; m[2] += a0 * a2
                m[3] += a1 * a0; m[4] += a1 * a1; m[5] += a1 * a2
                m[6] += a2 * a0; m[7] += a2 * a1; m[8] += a2 * a2
            }
        }
        val tv = eigMin(m)
        var t0 = tv[0].toFloat(); var t1 = tv[1].toFloat(); var t2 = tv[2].toFloat()
        // sentido: os pontos tem de estar NA FRENTE da camera (profundidade positiva)
        var pos = 0; var neg = 0
        for (k in 0 until n) {
            if (!keep[k]) continue
            val x2x = x2a[k * 3]; val x2y = x2a[k * 3 + 1]; val x2z = x2a[k * 3 + 2]
            val c1x = x2y * t2 - x2z * t1; val c1y = x2z * t0 - x2x * t2; val c1z = x2x * t1 - x2y * t0       // x2 x t
            val rx = rxa[k * 3]; val ry = rxa[k * 3 + 1]; val rz = rxa[k * 3 + 2]
            val c2x = x2y * rz - x2z * ry; val c2y = x2z * rx - x2x * rz; val c2z = x2x * ry - x2y * rx       // x2 x (R x1)
            val den = c2x * c2x + c2y * c2y + c2z * c2z + 1e-12f
            val z = -(c1x * c2x + c1y * c2y + c1z * c2z) / den
            if (z > 0f) pos++ else neg++
        }
        if (pos < neg) { t0 = -t0; t1 = -t1; t2 = -t2 }
        // deslocamento da camera no mundo: Delta = -B2 t
        val dwx = -(t0 * b2[0] + t1 * b2[3] + t2 * b2[6])
        val dwy = -(t0 * b2[1] + t1 * b2[4] + t2 * b2[7])
        val dwz = -(t0 * b2[2] + t1 * b2[5] + t2 * b2[8])
        val hn = hypot(dwx, dwz)
        val total = sqrt(dwx * dwx + dwy * dwy + dwz * dwz)
        if (total < 1e-6f || hn / total < 0.4f) { fail(); return }          // quase so vertical: ignora
        val nx = dwx / hn; val nz = dwz / hn
        var sx = dirX * 0.5f + nx * 0.5f
        var sz = dirZ * 0.5f + nz * 0.5f
        val sn = hypot(sx, sz)
        if (sn < 0.2f) { sx = nx; sz = nz } else { sx /= sn; sz /= sn }
        dirX = sx; dirZ = sz
        conf = min(1f, bestCount / 35f) * min(1f, par / 1.0f)
    }
}
