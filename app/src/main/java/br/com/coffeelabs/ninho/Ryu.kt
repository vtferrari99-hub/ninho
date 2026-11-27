package br.com.coffeelabs.ninho

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Proporções de cada fase do Ryu (interpoláveis para a animação de crescimento). */
class RyuShape(
    @JvmField val L: Float, @JvmField val R: Float, @JvmField val head: Float, @JvmField val eye: Float,
    @JvmField val antler: Float, @JvmField val whisker: Float, @JvmField val mane: Float,
    @JvmField val legs: Float = 1f, @JvmField val beard: Float = 0f, @JvmField val pale: Float = 0f
) {
    fun blend(o: RyuShape, t: Float) = RyuShape(
        lerp(L, o.L, t), lerp(R, o.R, t), lerp(head, o.head, t), lerp(eye, o.eye, t),
        lerp(antler, o.antler, t), lerp(whisker, o.whisker, t), lerp(mane, o.mane, t),
        lerp(legs, o.legs, t), lerp(beard, o.beard, t), lerp(pale, o.pale, t)
    )

    companion object {
        fun of(s: Stage): RyuShape = when (s) {
            Stage.EGG, Stage.BABY -> RyuShape(1.35f, 0.19f, 0.64f, 1.45f, 0.30f, 0.45f, 0.55f, 0.9f, 0f, 0.6f)
            Stage.CHILD -> RyuShape(2.0f, 0.19f, 0.64f, 1.28f, 0.55f, 0.65f, 0.75f, 1f, 0f, 0.45f)
            Stage.TEEN -> RyuShape(2.7f, 0.195f, 0.65f, 1.12f, 0.80f, 0.85f, 0.9f, 1f, 0f, 0.22f)
            Stage.ADULT -> RyuShape(3.3f, 0.205f, 0.66f, 1.0f, 1.0f, 1.0f, 1.0f)
            Stage.ELDER -> RyuShape(3.5f, 0.205f, 0.68f, 0.93f, 1.25f, 1.5f, 1.15f, 1f, 1f, 0.85f)
        }
    }
}

/** Estado de animação por quadro, preenchido pela lógica do jogo. */
class RyuPose {
    @JvmField var t = 0f
    @JvmField var open = 1f
    @JvmField var eyeMode = 0f      // 0 aberto, 1 feliz (^), 2 dormindo (u)
    @JvmField var jaw = 0.05f
    @JvmField var headF = V3(1f, 0f, 0.3f)
    @JvmField var headU = V3.UP
    @JvmField var paddle = 1f
    @JvmField var nod = 0f
}

class RyuBuilder {
    private val white = V3(1f, 1f, 1f)
    private val baseBody = BODY
    private val baseBelly = BELLY
    private val baseCrest = V3.rgb("#3E4656")
    private val baseMane = V3.rgb("#454C5A")
    private val baseHorn = V3.rgb("#8F887C")
    private val baseNose = V3.rgb("#444B58")
    private val mouth = V3.rgb("#E3A2AE")
    private val baseBrow = V3.rgb("#6C7482")
    private val dark = V3.rgb("#4A5568")

    private var radii = FloatArray(96)
    private var bodyN = FloatArray(96 * 3)
    private var bodyT = FloatArray(96 * 3)
    private val tmp5 = FloatArray(33)
    private val rad5 = FloatArray(11)
    private val wPts = FloatArray(26 * 3)
    private val wRad = FloatArray(26)
    private val aPts = FloatArray(12 * 3)
    private val aRad = FloatArray(12)
    private val bPts = FloatArray(15)
    private val bRad = FloatArray(5)

    private fun profile(s: Float): Float {
        if (s < 0.07f) return 0.78f + 0.1f * (s / 0.07f)
        if (s < 0.28f) return 0.88f + 0.12f * sin((s - 0.07f) / 0.21f * PI.toFloat() / 2f)
        val q = (s - 0.28f) / 0.72f
        return max(0.1f, 1f - q.pow(1.5f) * 0.9f)
    }

    private fun pt(a: FloatArray, k: Int) = V3(a[k * 3], a[k * 3 + 1], a[k * 3 + 2])

    /** spine: n pontos (cabeça → cauda) intercalados x,y,z. */
    fun build(m: Mesh, spine: FloatArray, n: Int, st: RyuShape, p: RyuPose) {
        val pale = st.pale
        val body = baseBody.lerp(SILVER, pale)
        val belly = baseBelly.lerp(white, pale * 0.8f)
        val crest = baseCrest.lerp(V3.rgb("#B9C6DA"), pale)
        val mane = baseMane.lerp(V3.rgb("#EEF2F7"), pale)
        val horn = baseHorn.lerp(V3.rgb("#C9C2B6"), pale * 0.6f)
        val claw = V3.rgb("#D9D4CA")
        val R = st.R
        if (radii.size < n) { radii = FloatArray(n); bodyN = FloatArray(n * 3); bodyT = FloatArray(n * 3) }
        for (k in 0 until n) radii[k] = R * profile(k / (n - 1f))
        m.tube(spine, n, radii, body, 1f, 28, 1f, p.headU, true)
        for (k in 0 until n) {
            val nn = m.lastNormal(k); val tt = m.lastTangent(k)
            bodyN[k * 3] = nn.x; bodyN[k * 3 + 1] = nn.y; bodyN[k * 3 + 2] = nn.z
            bodyT[k * 3] = tt.x; bodyT[k * 3 + 1] = tt.y; bodyT[k * 3 + 2] = tt.z
        }
        val t = p.t
        // espinhos dorsais
        var k = 3
        while (k < n - 5) {
            val s = k / (n - 1f)
            val N = pt(bodyN, k); val T = pt(bodyT, k)
            val h = R * 0.75f * (1f - 0.55f * s)
            m.cone(pt(spine, k) + N * (radii[k] * 0.8f), (N + T * 0.9f).norm(), h, R * 0.22f * (1f - 0.4f * s), crest, 0f, 10, 0.8f)
            k += 3
        }
        // pernas
        if (st.legs > 0.01f) {
            val legs = floatArrayOf(0.17f, 0f, 0.55f, 1.7f)
            for (li in 0..1) {
                val kk = (legs[li * 2] * (n - 1)).toInt()
                val ph = legs[li * 2 + 1]
                val N = pt(bodyN, kk); val Tk = pt(bodyT, kk); val B = Tk.cross(N)
                for (side in intArrayOf(-1, 1)) {
                    val a = PI.toFloat() / 2f + 0.55f
                    val S = N * cos(a) + B * (sin(a) * side)
                    val sw = sin(t * 2.2f + ph + (if (side > 0) 0f else PI.toFloat())) * 0.35f * p.paddle
                    val hip = pt(spine, kk) + S * (radii[kk] * 0.6f)
                    val ls = st.legs * R * 0.8f
                    val knee = hip + S * (1.25f * ls) + N * (-0.6f * ls) + Tk * ((0.2f + sw) * ls)
                    val foot = knee + N * (-1.1f * ls) + Tk * ((-0.5f - sw) * ls) + S * (0.25f * ls)
                    val ctrl = knee + (knee - hip.lerp(foot, 0.5f)) * 0.7f
                    for (j in 0..10) {
                        val u = j / 10f; val mu = 1f - u
                        tmp5[j * 3] = mu * mu * hip.x + 2f * mu * u * ctrl.x + u * u * foot.x
                        tmp5[j * 3 + 1] = mu * mu * hip.y + 2f * mu * u * ctrl.y + u * u * foot.y
                        tmp5[j * 3 + 2] = mu * mu * hip.z + 2f * mu * u * ctrl.z + u * u * foot.z
                        rad5[j] = lerp(0.5f, 0.3f, u) * ls
                    }
                    m.tube(tmp5, 11, rad5, body, 0f, 14, 1f, V3.UP, false)
                    m.ellipsoid(Frame(foot, Tk * -1f, N, 1f), 0f, 0f, 0f, 0.42f * ls, 0.3f * ls, 0.38f * ls, body, 0f, 18, 12)
                    val fwd = (Tk * -1f + N * -0.4f).norm()
                    for (c in -1..1) {
                        val cd = (fwd + S * (0.45f * c)).norm()
                        m.cone(foot + cd * (0.3f * ls), cd, 0.45f * ls, 0.1f * ls, claw, 0f, 8, 0.6f, N * -0.4f)
                    }
                    m.cone(knee, (Tk + S * 0.6f + N * 0.3f).norm(), 1.0f * ls * st.mane, 0.3f * ls, mane, 0f, 10, 0.8f, N * 0.25f)
                }
            }
        }
        // tufo da cauda
        run {
            val tip = pt(spine, n - 1); val Tt = pt(bodyT, n - 1); val N = pt(bodyN, n - 1); val B = Tt.cross(N)
            for (j in 0..4) {
                val a = (j - 2) * 0.45f
                val d = (Tt + N * (0.5f * cos(a) + 0.2f) + B * (0.6f * sin(a))).norm()
                val sw = sin(t * 2f + j) * 0.2f
                m.cone(tip - Tt * (R * 0.6f), d, R * (2.6f - abs(j - 2) * 0.4f) * st.mane, R * 0.42f, mane, 0f, 10, 0.8f, N * (0.4f + sw) + B * sw)
            }
        }
        buildHead(m, pt(spine, 0), st, p, body, belly, mane, horn)
    }

    private fun buildHead(m: Mesh, neck: V3, st: RyuShape, p: RyuPose, body: V3, belly: V3, mane: V3, horn: V3) {
        val t = p.t
        val hs = st.head
        val F = p.headF.norm()
        val o = neck + F * (0.22f * hs)
        val fr0 = Frame(o, F, p.headU, hs)
        // aceno da cabeça (carinho)
        val fr = if (abs(p.nod) > 0.001f) Frame(o, fr0.d(cos(p.nod), sin(p.nod), 0f), fr0.d(-sin(p.nod), cos(p.nod), 0f), hs) else fr0
        val brow = baseBrow.lerp(SILVER, st.pale)
        m.ellipsoid(fr, 0f, 0.08f, 0f, 0.5f, 0.46f, 0.44f, body, 0f)
        m.ellipsoid(fr, 0.48f, -0.08f, 0f, 0.46f, 0.23f, 0.26f, body, 0f)
        m.ellipsoid(fr, 0.88f, -0.01f, 0f, 0.12f, 0.13f, 0.17f, body.lerp(baseNose, 0.5f), 0f, 20, 14)
        for (z in floatArrayOf(-0.08f, 0.08f)) m.ellipsoid(fr, 0.97f, 0.05f, z, 0.022f, 0.02f, 0.026f, dark, 0f, 12, 8, 0f)
        val jaw = p.jaw
        val hx = 0.02f; val hy = -0.2f
        val ang = -jaw * 0.55f
        val ca = cos(ang); val sa = sin(ang)
        fun rx(x: Float, y: Float) = hx + (x - hx) * ca - (y - hy) * sa
        fun ry(x: Float, y: Float) = hy + (x - hx) * sa + (y - hy) * ca
        if (jaw > 0.08f) m.ellipsoid(fr, rx(0.42f, -0.22f), ry(0.42f, -0.22f) + 0.03f, 0f, 0.32f, 0.08f, 0.2f, mouth, 0f, 26, 16, 0f)
        val jf = Frame(fr.p(rx(0.42f, -0.27f), ry(0.42f, -0.27f), 0f), fr.d(ca, sa, 0f), fr.d(-sa, ca, 0f), hs)
        m.ellipsoid(jf, 0f, 0f, 0f, 0.38f, 0.11f, 0.25f, body.lerp(belly, 0.55f), 0f)
        for (z in intArrayOf(-1, 1)) {
            m.ellipsoid(Frame(fr.p(0.27f, 0.37f, 0.27f * z), fr.d(1f, 0.2f, -0.35f * z), fr.u, hs), 0f, 0f, 0f, 0.2f, 0.07f, 0.1f, brow, 0f, 20, 12)
        }
        // olhos
        val er = 0.17f * st.eye
        val eyeCol = V3(p.open, p.eyeMode, 0f)
        for (z in intArrayOf(-1, 1)) {
            m.ellipsoid(fr, 0.25f, 0.14f, 0.31f * z, er, er * 1.1f, er, eyeCol, 3f, 36, 26, 0.7f, 1, V3(0.55f, 0.08f, 0.83f * z))
        }
        // galhada
        val a = st.antler
        if (a > 0.01f) {
            for (z in intArrayOf(-1, 1)) {
                for (i in 0..11) {
                    val j = i * 5f / 11f
                    val q = fr.p(-0.12f - 0.05f * j * a, 0.38f + 0.17f * j * a - 0.012f * j * j * a, (0.18f + 0.03f * j) * z)
                    aPts[i * 3] = q.x; aPts[i * 3 + 1] = q.y; aPts[i * 3 + 2] = q.z; aRad[i] = 0.075f - 0.009f * j
                }
                m.tube(aPts, 12, aRad, horn, 0f, 14, 0.8f)
                if (a > 0.45f) {
                    val b = V3(aPts[12], aPts[13], aPts[14]); val q = fr.p(0.06f, 0.38f + 0.62f * a, 0.27f * z)
                    val cc = b.lerp(q, 0.5f) + fr.u * (0.08f * a * fr.s)
                    for (i in 0..4) { val u = i / 4f; val w = b * ((1f - u) * (1f - u)) + cc * (2f * u * (1f - u)) + q * (u * u)
                        bPts[i * 3] = w.x; bPts[i * 3 + 1] = w.y; bPts[i * 3 + 2] = w.z; bRad[i] = lerp(0.045f, 0.02f, u) }
                    m.tube(bPts, 5, bRad, horn, 0f, 12, 0.8f)
                }
                if (a > 0.9f) {
                    val b = V3(aPts[24], aPts[25], aPts[26]); val q = fr.p(-0.15f - 0.2f * a, 0.5f + 0.9f * a, 0.22f * z)
                    for (i in 0..4) { val u = i / 4f; val w = b.lerp(q, u)
                        bPts[i * 3] = w.x; bPts[i * 3 + 1] = w.y; bPts[i * 3 + 2] = w.z; bRad[i] = lerp(0.035f, 0.018f, u) }
                    m.tube(bPts, 5, bRad, horn, 0f, 12, 0.8f)
                }
            }
        }
        // crina
        val mn = st.mane
        val tufts = floatArrayOf(
            -0.38f, 0.3f, 0f, -1f, 0.4f, 0f,
            -0.38f, 0.12f, 0.26f, -1f, 0.1f, 0.55f,
            -0.38f, 0.12f, -0.26f, -1f, 0.1f, -0.55f,
            -0.12f, -0.24f, 0.32f, -0.7f, -0.3f, 0.8f,
            -0.12f, -0.24f, -0.32f, -0.7f, -0.3f, -0.8f
        )
        for (j in 0 until 5) {
            val sw = sin(t * 1.6f + j * 0.9f) * 0.12f
            val b = j * 6
            m.cone(fr.p(tufts[b], tufts[b + 1], tufts[b + 2]), fr.d(tufts[b + 3], tufts[b + 4], tufts[b + 5]), 0.48f * hs * mn, 0.2f * hs,
                mane, 0f, 14, 0.8f, fr.d(0.1f, 0.45f + sw, sw))
        }
        // bigodes
        val wl = st.whisker
        for (z in intArrayOf(-1, 1)) {
            for (j in 0 until 26) {
                val u = j / 25f
                val sway = sin(t * 1.5f - u * 3f + (if (z > 0) 0f else 1.3f)) * 0.22f * u
                val x = 0.86f + 0.25f * sin(u * 2.2f) * wl - u * 0.9f * wl
                val y = -0.06f - 0.45f * u * wl + sway
                val zz = (0.2f + 1.0f * u * wl) * z
                val q = fr.p(x, y, zz)
                wPts[j * 3] = q.x; wPts[j * 3 + 1] = q.y; wPts[j * 3 + 2] = q.z; wRad[j] = 0.022f * (1f - u * 0.7f)
            }
            m.tube(wPts, 26, wRad, mane, 0f, 10, 0.6f)
        }
        if (st.beard > 0.5f) {
            for (j in 0..2) {
                val zz = (j - 1) * 0.1f
                m.cone(fr.p(0.42f, -0.33f, zz), fr.d(-0.3f, -1f, zz), 0.45f * st.beard, 0.08f, mane, 0f, 10, 0.7f, fr.d(-0.5f, 0f, 0f))
            }
        }
    }

    // ------------------------------------------------------------ plantas, ovo, pérola
    private val padP = FloatArray(12 * 80 * 3)
    private val padE = FloatArray(12 * 80 * 3)
    private val petP = FloatArray(12 * 9 * 3)
    private val petE = FloatArray(12 * 9 * 3)
    private val green = V3(0.4f, 0.6f, 0.4f)
    private val petalC = V3(1f, 0.8f, 0.85f)
    private val yellow = V3.rgb("#E2C85A")
    private val yellow2 = V3.rgb("#F0D46A")
    private val stem = V3.rgb("#6FA06A")

    /** Folha de vitória-régia/nenúfar com recorte em V e borda levemente erguida. */
    fun pad(m: Mesh, cx: Float, cz: Float, radius: Float, notchDir: Float, notch: Float = 0.45f) {
        val R = 12; val S = 80
        val y = WATER_Y + 0.012f
        for (r in 0 until R) {
            val rr = r / (R - 1f)
            for (s in 0 until S) {
                val a = notchDir + notch / 2f + (2f * PI.toFloat() - notch) * s / (S - 1f)
                val wav = 1f + 0.035f * sin(a * 7f) * rr
                val x = cos(a) * rr * wav; val z = sin(a) * rr * wav
                val yy = 0.05f * rr.pow(5) + 0.012f * sin(a * 5f + 1f) * rr * rr
                val k = (r * S + s) * 3
                padP[k] = cx + x * radius; padP[k + 1] = y + yy * radius; padP[k + 2] = cz + z * radius
                padE[k] = x; padE[k + 1] = z; padE[k + 2] = rr
            }
        }
        m.surface(padP, padE, R, S, green, 7f)
    }

    private fun petal(m: Mesh, base: V3, out: V3, tilt: Float, length: Float, width: Float, cup: Float = 0.35f) {
        val A = V3(out.x * sin(tilt), cos(tilt), out.z * sin(tilt)).norm()
        val Wd = (if (abs(cos(tilt)) > 0.05f) A.cross(V3(out.x, 0f, out.z)) else V3.UP.cross(out)).norm()
        var Nn = Wd.cross(A).norm()
        if (Nn.dot(V3(-out.x, 0.3f, -out.z)) < 0f) Nn = Nn * -1f
        val R = 12; val C = 9
        for (r in 0 until R) {
            val u = r / (R - 1f)
            val shape = sin(PI.toFloat() * minOf(1f, u * 0.92f + 0.06f)).pow(0.75f) * (1f - 0.3f * u)
            for (c in 0 until C) {
                val v = c / (C - 1f) * 2f - 1f
                val al = length * u; val wd = width * v * shape; val nn = cup * width * shape * v * v - length * 0.12f * u * u
                val k = (r * C + c) * 3
                petP[k] = base.x + A.x * al + Wd.x * wd + Nn.x * nn
                petP[k + 1] = base.y + A.y * al + Wd.y * wd + Nn.y * nn
                petP[k + 2] = base.z + A.z * al + Wd.z * wd + Nn.z * nn
                petE[k] = u; petE[k + 1] = v; petE[k + 2] = 0f
            }
        }
        m.surface(petP, petE, R, C, petalC, 6f)
    }

    fun flower(m: Mesh, cx: Float, cz: Float, scale: Float) {
        val y = WATER_Y + 0.03f
        val rings = floatArrayOf(11f, 1.22f, 0.40f, 0.115f, 0.05f, 9f, 0.85f, 0.36f, 0.11f, 0.035f, 8f, 0.5f, 0.31f, 0.10f, 0.02f, 6f, 0.22f, 0.25f, 0.085f, 0.008f)
        for (ri in 0..3) {
            val cnt = rings[ri * 5].toInt(); val tilt = rings[ri * 5 + 1]; val ln = rings[ri * 5 + 2]; val wd = rings[ri * 5 + 3]; val rad = rings[ri * 5 + 4]
            for (j in 0 until cnt) {
                val a = (2.0 * PI * (j + 0.5 * (ri % 2)) / cnt).toFloat() + 0.3f * ri
                val out = V3(cos(a), 0f, sin(a))
                petal(m, V3(cx + out.x * rad * scale, y + 0.012f * ri * scale, cz + out.z * rad * scale), out, tilt, ln * scale, wd * scale)
            }
        }
        val fr = Frame(V3(cx, y + 0.05f * scale, cz), V3(1f, 0f, 0f), V3.UP, scale)
        m.ellipsoid(fr, 0f, 0f, 0f, 0.06f, 0.045f, 0.06f, yellow, 8f, 20, 14, 0f)
        for (j in 0 until 14) {
            val a = (2.0 * PI * j / 14).toFloat()
            m.cone(V3(cx + cos(a) * 0.05f * scale, y + 0.05f * scale, cz + sin(a) * 0.05f * scale), V3(cos(a) * 0.6f, 1f, sin(a) * 0.6f).norm(),
                0.09f * scale, 0.012f * scale, yellow2, 8f, 8, 0f)
        }
    }

    fun bud(m: Mesh, cx: Float, cz: Float, scale: Float, lean: Float = 0.15f) {
        val y = WATER_Y
        val top = V3(cx + lean * scale, y + 0.42f * scale, cz)
        val rr = 0.018f * scale
        m.tube(floatArrayOf(cx, y - 0.05f, cz, cx + lean * 0.3f * scale, y + 0.2f * scale, cz, top.x, top.y, top.z), 3, floatArrayOf(rr, rr, rr), stem, 8f, 12, 0f, V3(0f, 0f, 1f), false)
        val fr = Frame(top, V3(0f, 0f, 1f), V3(lean, 1f, 0f).norm(), scale)
        m.ellipsoid(fr, 0f, 0.1f, 0f, 0.075f, 0.15f, 0.075f, petalC, 6f, 26, 18, 0f, 4, null, 0.55f)
    }

    /** Lago: folha grande (o ninho) + folhas menores, uma flor aberta e um botão. */
    fun pond(m: Mesh) {
        pad(m, 0f, 0.6f, 0.74f, 1.9f)
        pad(m, 0.98f, 0.05f, 0.36f, 4.0f)
        pad(m, -0.9f, 1.0f, 0.3f, 0.6f)
        pad(m, -1.05f, -0.35f, 0.42f, 5.3f)
        flower(m, -0.98f, -0.3f, 0.95f)
        bud(m, 1.0f, 0.12f, 0.9f)
    }

    fun egg(m: Mesh, c: V3, size: Float, wobble: Float, crack: Float, glow: Float) {
        val fr = Frame(c, V3(0f, 0f, 1f), V3(-sin(wobble), cos(wobble), 0f), size)
        m.ellipsoid(fr, 0f, 0.5f, 0f, 0.36f, 0.5f, 0.36f, V3(crack, glow, 0f), 5f, 44, 30, 1f, 0, null, 0.16f)
    }

    fun pearl(m: Mesh, c: V3, r: Float) {
        m.ellipsoid(Frame(c, V3(1f, 0f, 0f), V3.UP, 1f), 0f, 0f, 0f, r, r, r, white, 4f, 36, 24, 0.7f)
    }

    companion object {
        const val WATER_Y = -1.2f
        val BODY = V3.rgb("#59606D")
        val BELLY = V3.rgb("#B4B9C2")
        val SILVER = V3.rgb("#D3D9E2")
        val NEST = V3(0f, WATER_Y + 0.02f, 0.6f)
    }
}
