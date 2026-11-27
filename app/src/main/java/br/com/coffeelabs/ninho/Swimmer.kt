package br.com.coffeelabs.ninho

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Cabeça que nada em direção a um alvo, deixando um rastro que o corpo segue (movimento serpentino). */
class Swimmer(start: V3) {
    var head = start
    var vel = V3(0.3f, 0f, 0f)
    val trail = ArrayList<V3>()
    private var wig = 0f

    init { trail.add(start) }

    fun step(dt: Float, target: V3, maxSpeed: Float, L: Float, wiggleAmp: Float) {
        val d = target - head
        val dist = d.len()
        val sp = min(maxSpeed, dist * 1.2f)
        val des = if (dist > 1e-4f) d.norm() * sp else V3.ZERO
        val k = min(1f, dt * 1.8f)
        vel = vel + (des - vel) * k
        val vl = vel.len()
        var w = V3.ZERO
        if (vl > 1e-3f) {
            val side = vel.cross(V3.UP).norm()
            val up = side.cross(vel).norm()
            wig += dt * 2.4f
            w = side * (sin(wig) * wiggleAmp * vl) + up * (cos(wig * 0.7f) * wiggleAmp * 0.5f * vl)
        }
        head = head + (vel + w) * dt
        if ((head - trail[0]).len() > L / 90f) trail.add(0, head)
        var total = 0f
        var i = 1
        while (i < trail.size) {
            total += (trail[i] - trail[i - 1]).len()
            if (total > L * 1.3f) {
                while (trail.size > i + 1) trail.removeAt(trail.size - 1)
                break
            }
            i++
        }
    }

    /** Substitui o rastro por uma coluna pronta (ex.: ao acordar enrolado). */
    fun setFromSpine(spine: FloatArray, n: Int) {
        trail.clear()
        for (k in 0 until n) trail.add(V3(spine[k * 3], spine[k * 3 + 1], spine[k * 3 + 2]))
        head = trail[0]
        vel = V3.ZERO
    }

    /** Amostra n pontos igualmente espaçados ao longo do rastro, cobrindo o comprimento L. */
    fun spine(out: FloatArray, n: Int, L: Float) {
        val seg = L / (n - 1)
        var cx = head.x; var cy = head.y; var cz = head.z
        out[0] = cx; out[1] = cy; out[2] = cz
        var cnt = 1
        var i = 0
        var need = seg
        while (cnt < n) {
            if (i < trail.size) {
                val b = trail[i]
                val dx = b.x - cx; val dy = b.y - cy; val dz = b.z - cz
                val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (dl >= need && dl > 1e-6f) {
                    val f = need / dl
                    cx += dx * f; cy += dy * f; cz += dz * f
                    out[cnt * 3] = cx; out[cnt * 3 + 1] = cy; out[cnt * 3 + 2] = cz
                    cnt++; need = seg
                } else {
                    need -= dl; cx = b.x; cy = b.y; cz = b.z; i++
                }
            } else {
                var dx: Float; var dy: Float; var dz: Float
                if (cnt >= 2) {
                    dx = out[(cnt - 1) * 3] - out[(cnt - 2) * 3]; dy = out[(cnt - 1) * 3 + 1] - out[(cnt - 2) * 3 + 1]; dz = out[(cnt - 1) * 3 + 2] - out[(cnt - 2) * 3 + 2]
                    val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).let { if (it < 1e-6f) 1f else it }
                    dx /= l; dy /= l; dz /= l
                } else { dx = -1f; dy = 0f; dz = 0f }
                out[cnt * 3] = out[(cnt - 1) * 3] + dx * seg; out[cnt * 3 + 1] = out[(cnt - 1) * 3 + 1] + dy * seg; out[cnt * 3 + 2] = out[(cnt - 1) * 3 + 2] + dz * seg
                cnt++
            }
        }
    }
}
