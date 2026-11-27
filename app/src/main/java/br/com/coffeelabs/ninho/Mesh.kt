package br.com.coffeelabs.ninho

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class V3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 {
        val l = len()
        return if (l > 1e-6f) V3(x / l, y / l, z / l) else V3(0f, 1f, 0f)
    }
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val UP = V3(0f, 1f, 0f)
        val ZERO = V3(0f, 0f, 0f)
        fun rgb(h: String): V3 {
            val c = hex(h)
            return V3(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
        }
    }
}

/** Base ortonormal: f (frente), u (cima), r (direita), com origem e escala. */
class Frame(@JvmField val o: V3, f: V3, up: V3, @JvmField val s: Float) {
    @JvmField val f: V3 = f.norm()
    @JvmField val r: V3 = this.f.cross(up).norm()
    @JvmField val u: V3 = r.cross(this.f)
    fun p(x: Float, y: Float, z: Float) = V3(
        o.x + (f.x * x + u.x * y + r.x * z) * s,
        o.y + (f.y * x + u.y * y + r.y * z) * s,
        o.z + (f.z * x + u.z * y + r.z * z) * s
    )
    fun d(x: Float, y: Float, z: Float) = V3(f.x * x + u.x * y + r.x * z, f.y * x + u.y * y + r.y * z, f.z * x + u.z * y + r.z * z).norm()
}

/** Malha dinâmica: pos3 nrm3 col3 ext4 contorno1 = 14 floats por vértice. */
class Mesh {
    companion object { const val STRIDE = 14 }

    var v = FloatArray(STRIDE * 30000)
    var vn = 0
    var idx = IntArray(180000)
    var inN = 0

    fun reset() { vn = 0; inN = 0 }
    fun count() = vn / STRIDE

    private fun ensureV(extra: Int) {
        if (vn + extra > v.size) v = v.copyOf(maxOf(v.size * 2, vn + extra))
    }
    private fun ensureI(extra: Int) {
        if (inN + extra > idx.size) idx = idx.copyOf(maxOf(idx.size * 2, inN + extra))
    }

    fun vert(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, c: V3, e0: Float, e1: Float, e2: Float, e3: Float, ol: Float) {
        ensureV(STRIDE)
        val a = v; var k = vn
        a[k++] = px; a[k++] = py; a[k++] = pz; a[k++] = nx; a[k++] = ny; a[k++] = nz
        a[k++] = c.x; a[k++] = c.y; a[k++] = c.z; a[k++] = e0; a[k++] = e1; a[k++] = e2; a[k++] = e3; a[k++] = ol
        vn = k
    }

    fun tri(a: Int, b: Int, c: Int) {
        ensureI(3)
        idx[inN++] = a; idx[inN++] = b; idx[inN++] = c
    }

    fun grid(base: Int, cols: Int, rows: Int) {
        for (r in 0 until rows - 1) for (c in 0 until cols) {
            val c2 = (c + 1) % cols
            val a = base + r * cols + c; val b = base + r * cols + c2
            val d = base + (r + 1) * cols + c; val e = base + (r + 1) * cols + c2
            tri(a, b, d); tri(b, e, d)
        }
    }

    /**
     * Elipsoide no espaço local de [fr]. mode: 0 = ext com coordenada da esfera unitária,
     * 1 = olho (eixo eye*), 2 = folha (x,z,y), 3 = pétala ((x+1)/2, z, y).
     */
    fun ellipsoid(
        fr: Frame, cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, col: V3, mat: Float,
        seg: Int = 30, rings: Int = 20, ol: Float = 1f, mode: Int = 0, eye: V3? = null, egg: Float = 0f
    ) {
        val base = count()
        var esx = 0f; var esy = 0f; var esz = 0f; var eux = 0f; var euy = 0f; var euz = 0f; var ex = 0f; var ey = 0f; var ez = 0f
        if (eye != null) {
            val e = eye.norm()
            val eu = (V3.UP - e * e.y).norm()
            val es = e.cross(eu)
            ex = e.x; ey = e.y; ez = e.z; eux = eu.x; euy = eu.y; euz = eu.z; esx = es.x; esy = es.y; esz = es.z
        }
        val f = fr.f; val u = fr.u; val r = fr.r; val o = fr.o; val s = fr.s
        for (ri in 0..rings) {
            val th = (PI * ri / rings).toFloat()
            val st = sin(th); val ct = cos(th)
            for (si in 0 until seg) {
                val ph = (2.0 * PI * si / seg).toFloat()
                val ux = st * cos(ph); val uy = ct; val uz = st * sin(ph)
                val k = if (egg != 0f) 1f - egg * uy else 1f
                val lx = cx + ux * rx * k; val ly = cy + uy * ry; val lz = cz + uz * rz * k
                var nlx = ux / rx; var nly = uy / ry; var nlz = uz / rz
                val nl = sqrt(nlx * nlx + nly * nly + nlz * nlz); nlx /= nl; nly /= nl; nlz /= nl
                val px = o.x + (f.x * lx + u.x * ly + r.x * lz) * s
                val py = o.y + (f.y * lx + u.y * ly + r.y * lz) * s
                val pz = o.z + (f.z * lx + u.z * ly + r.z * lz) * s
                var nx = f.x * nlx + u.x * nly + r.x * nlz
                var ny = f.y * nlx + u.y * nly + r.y * nlz
                var nz = f.z * nlx + u.z * nly + r.z * nlz
                val nn = sqrt(nx * nx + ny * ny + nz * nz); nx /= nn; ny /= nn; nz /= nn
                when (mode) {
                    1 -> vert(px, py, pz, nx, ny, nz, col, ux * esx + uy * esy + uz * esz, ux * eux + uy * euy + uz * euz, ux * ex + uy * ey + uz * ez, mat, ol)
                    2 -> vert(px, py, pz, nx, ny, nz, col, ux, uz, uy, mat, ol)
                    3 -> vert(px, py, pz, nx, ny, nz, col, (ux + 1f) / 2f, uz, uy, mat, ol)
                    4 -> vert(px, py, pz, nx, ny, nz, col, (uy + 1f) / 2f, ux, 0f, mat, ol)
                    else -> vert(px, py, pz, nx, ny, nz, col, ux, uy, uz, mat, ol)
                }
            }
        }
        grid(base, seg, rings + 1)
    }

    // buffers temporários do tubo
    private var tT = FloatArray(0)
    private var tN = FloatArray(0)

    /** Tubo ao longo de pts (x,y,z intercalados), com transporte paralelo do referencial. */
    fun tube(pts: FloatArray, n: Int, radii: FloatArray, col: V3, mat: Float, sides: Int = 10, ol: Float = 1f, up: V3 = V3.UP, capTip: Boolean = true) {
        if (tT.size < n * 3) { tT = FloatArray(n * 3); tN = FloatArray(n * 3) }
        for (k in 0 until n) {
            val a = maxOf(0, k - 1) * 3; val b = minOf(n - 1, k + 1) * 3
            var dx = pts[b] - pts[a]; var dy = pts[b + 1] - pts[a + 1]; var dz = pts[b + 2] - pts[a + 2]
            val l = sqrt(dx * dx + dy * dy + dz * dz).let { if (it < 1e-6f) 1f else it }
            dx /= l; dy /= l; dz /= l
            tT[k * 3] = dx; tT[k * 3 + 1] = dy; tT[k * 3 + 2] = dz
        }
        // normal inicial
        var nx = up.x; var ny = up.y; var nz = up.z
        run {
            val d = nx * tT[0] + ny * tT[1] + nz * tT[2]
            if (abs(d) > 0.98f) { nx = 0f; ny = -tT[2]; nz = tT[1]; if (abs(ny) + abs(nz) < 1e-3f) { nx = 0f; ny = 0f; nz = 1f } }
        }
        val base = count()
        for (k in 0 until n) {
            val tx = tT[k * 3]; val ty = tT[k * 3 + 1]; val tz = tT[k * 3 + 2]
            val d = nx * tx + ny * ty + nz * tz
            nx -= tx * d; ny -= ty * d; nz -= tz * d
            val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
            nx /= l; ny /= l; nz /= l
            val bx = ty * nz - tz * ny; val by = tz * nx - tx * nz; val bz = tx * ny - ty * nx
            tN[k * 3] = nx; tN[k * 3 + 1] = ny; tN[k * 3 + 2] = nz
            val rr = radii[k]
            val px = pts[k * 3]; val py = pts[k * 3 + 1]; val pz = pts[k * 3 + 2]
            for (s in 0 until sides) {
                val a = (2.0 * PI * s / sides).toFloat()
                val ca = cos(a); val sa = sin(a)
                val dx = nx * ca + bx * sa; val dy = ny * ca + by * sa; val dz = nz * ca + bz * sa
                vert(px + dx * rr, py + dy * rr, pz + dz * rr, dx, dy, dz, col, k.toFloat() / (n - 1), s.toFloat() / sides, 0f, mat, ol)
            }
        }
        grid(base, sides, n)
        if (capTip) {
            val l3 = (n - 1) * 3
            val tip = count()
            val rl = radii[n - 1] * 0.8f
            vert(pts[l3] + tT[l3] * rl, pts[l3 + 1] + tT[l3 + 1] * rl, pts[l3 + 2] + tT[l3 + 2] * rl, tT[l3], tT[l3 + 1], tT[l3 + 2], col, 1f, 0f, 0f, mat, ol)
            val last = base + (n - 1) * sides
            for (s in 0 until sides) tri(last + s, last + (s + 1) % sides, tip)
            val c0 = count()
            val r0 = radii[0] * 0.8f
            vert(pts[0] - tT[0] * r0, pts[1] - tT[1] * r0, pts[2] - tT[2] * r0, -tT[0], -tT[1], -tT[2], col, 0f, 0f, 0f, mat, ol)
            for (s in 0 until sides) tri(base + (s + 1) % sides, base + s, c0)
        }
    }

    /** Normal (dorsal) calculada no último tubo, ponto k. */
    fun lastNormal(k: Int) = V3(tN[k * 3], tN[k * 3 + 1], tN[k * 3 + 2])
    fun lastTangent(k: Int) = V3(tT[k * 3], tT[k * 3 + 1], tT[k * 3 + 2])

    private val conePts = FloatArray(30)
    private val coneRad = FloatArray(10)

    /** Cone (tufo/espinho) com curvatura opcional. */
    fun cone(b: V3, dir: V3, length: Float, radius: Float, col: V3, mat: Float, sides: Int = 12, ol: Float = 1f, bend: V3? = null) {
        for (k in 0..9) {
            val t = k / 9f
            var x = b.x + dir.x * length * t; var y = b.y + dir.y * length * t; var z = b.z + dir.z * length * t
            if (bend != null) { x += bend.x * length * t * t; y += bend.y * length * t * t; z += bend.z * length * t * t }
            conePts[k * 3] = x; conePts[k * 3 + 1] = y; conePts[k * 3 + 2] = z
            coneRad[k] = radius * (1f - t).pow(0.9f) + 0.002f
        }
        tube(conePts, 10, coneRad, col, mat, sides, ol)
    }

    /** Superfície aberta (rows x cols) com normais por diferenças finitas. P e E têm 3 floats por ponto. */
    fun surface(P: FloatArray, E: FloatArray, rows: Int, cols: Int, col: V3, mat: Float) {
        val base = count()
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = (maxOf(0, r - 1) * cols + c) * 3; val b = (minOf(rows - 1, r + 1) * cols + c) * 3
            val d = (r * cols + maxOf(0, c - 1)) * 3; val e = (r * cols + minOf(cols - 1, c + 1)) * 3
            val ux = P[e] - P[d]; val uy = P[e + 1] - P[d + 1]; val uz = P[e + 2] - P[d + 2]
            val wx = P[b] - P[a]; val wy = P[b + 1] - P[a + 1]; val wz = P[b + 2] - P[a + 2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-9f) 1f else it }
            nx /= l; ny /= l; nz /= l
            val k = (r * cols + c) * 3
            vert(P[k], P[k + 1], P[k + 2], nx, ny, nz, col, E[k], E[k + 1], E[k + 2], mat, 0f)
        }
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            val a = base + r * cols + c; val b = a + 1; val d = a + cols; val e = d + 1
            tri(a, b, d); tri(b, e, d)
        }
    }
}
