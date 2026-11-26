package br.com.coffeelabs.ninho

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Expr { CALM, JOY, BLISS, LOVE, NEUTRAL, SAD, SLEEP, SLEEPY, CHEW, SURPRISED, ANNOYED }

class DragonColors(
    val body: Int, val shade: Int, val light: Int, val belly: Int, val bellyLine: Int, val outline: Int,
    val membrane: Int, val vein: Int, val crest: Int, val horn: Int, val hornLine: Int,
    val iris: Int, val irisDark: Int, val pupil: Int, val blush: Int, val speck: Int, val mouth: Int
) {
    fun tinted(night: Float, elder: Boolean): DragonColors {
        fun t(c: Int, eye: Boolean = false, keep: Boolean = false): Int {
            var x = c
            if (elder && !eye && !keep) x = lerpColor(x, 0xFFFFFFFF.toInt(), 0.22f)
            return lerpColor(x, NIGHT, night * (if (eye) 0.18f else 0.42f))
        }
        return DragonColors(
            t(body), t(shade), t(light), t(belly), t(bellyLine), t(outline),
            t(membrane), t(vein), t(crest), t(horn), t(hornLine),
            t(iris, true), t(irisDark, true), t(pupil, true), t(blush, keep = true), t(speck), t(mouth, keep = true)
        )
    }

    companion object {
        val NIGHT = hex("#464E78")
        val SILVER = DragonColors(
            body = hex("#CBD1DB"), shade = hex("#A3ACBB"), light = hex("#F1F3F7"),
            belly = hex("#EAEDF2"), bellyLine = hex("#CCD2DC"), outline = hex("#858FA1"),
            membrane = hex("#DCE2EC"), vein = hex("#AFB9C9"), crest = hex("#B6C7E0"),
            horn = hex("#F3EEE5"), hornLine = hex("#C8BEAD"),
            iris = hex("#9DCBF2"), irisDark = hex("#5C93D3"), pupil = hex("#35598C"),
            blush = hex("#F3C3CF"), speck = hex("#A9C6E8"), mouth = hex("#E7A7B5")
        )
    }
}

class Build(
    val bodyCy: Float, val bodyRx: Float, val bodyRy: Float,
    val headX: Float, val headY: Float, val headR: Float,
    val snoutX: Float, val snoutY: Float, val snoutRx: Float, val snoutRy: Float,
    val eyeX: Float, val eyeY: Float, val eye: Float,
    val wing: Float, val horn: Float, val tail: Float, val tailW: Float,
    val crest: Float, val leg: Float, val neckW: Float, val elder: Boolean = false
)

/** Parâmetros de animação de um quadro. */
class Pose {
    var t = 0f
    var breath = 0f
    var walk = 0f
    var walkPhase = 0f
    var sleep = 0f
    var blink = 0f
    var expr = Expr.CALM
    var lookX = 0f
    var lookY = 0f
    var headTilt = 0f
    var flap = 0f
    var wingPhase = 0f
    var squash = 0f
    var wiggle = 0f
    var night = 0f
}

/**
 * Desenha o dragão em coordenadas próprias: 100 unidades = altura do dragão,
 * pés em y = 0, olhando para +x.
 */
class DragonRenderer(private val base: DragonColors) {

    companion object {
        const val OW = 1.7f
        const val TAIL_N = 24
        /** largura total / altura (para caber na tela). */
        const val WIDTH_FACTOR = 1.15f

        val BUILDS: EnumMap<Stage, Build> = EnumMap<Stage, Build>(Stage::class.java).apply {
            put(Stage.BABY, Build(-27f, 27f, 24f, 12f, -66f, 29f, 19f, 9f, 15f, 12f, 6f, -2f, 1.35f, 0.42f, 0.25f, 0.6f, 9f, 0.6f, 0.85f, 20f))
            put(Stage.CHILD, Build(-29f, 28f, 25f, 14f, -69f, 26.5f, 18f, 8.5f, 15.5f, 11.5f, 5.5f, -2.5f, 1.2f, 0.6f, 0.5f, 0.78f, 9.5f, 0.8f, 0.95f, 19f))
            put(Stage.TEEN, Build(-31f, 29f, 26.5f, 16f, -73f, 24f, 18f, 8f, 16.5f, 11f, 5f, -3f, 1.07f, 0.82f, 0.8f, 0.92f, 11.5f, 0.95f, 1.0f, 18f))
            put(Stage.ADULT, Build(-32f, 31f, 28f, 19f, -76f, 22.5f, 18f, 7.5f, 17f, 11f, 5f, -3f, 1.0f, 1.0f, 1.0f, 1.0f, 12f, 1.0f, 1.0f, 18f))
            put(Stage.ELDER, Build(-31f, 31f, 28f, 21f, -72f, 22.5f, 18f, 8f, 17f, 11f, 5f, -2f, 0.94f, 1.02f, 1.25f, 1.05f, 12f, 1.0f, 1.0f, 18f, true))
        }

        fun build(s: Stage): Build = BUILDS[s] ?: BUILDS[Stage.BABY]!!

        private val EGG_SPECKS = floatArrayOf(
            -18f, -52f, 3.2f, 10f, -78f, 2.4f, 20f, -40f, 3.6f, -6f, -28f, 2.6f, -26f, -30f, 2.2f, 6f, -60f, 2.0f,
            24f, -64f, 2.2f, -12f, -84f, 1.8f, 14f, -18f, 2.4f, -20f, -14f, 1.6f, 0f, -44f, 1.5f
        )
        private val CRACK = floatArrayOf(0f, -58f, -8f, -50f, -16f, -59f, -24f, -51f, -32f, -56f, 8f, -50f, 16f, -60f, 24f, -52f, 32f, -57f)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val headPaths = EnumMap<Stage, Path>(Stage::class.java)
    private val wingPath = Path().apply {
        moveTo(0f, 0f); cubicTo(-4f, -16f, -16f, -34f, -30f, -42f); quadTo(-40f, -46f, -47f, -38f)
        quadTo(-40f, -33f, -42f, -24f); quadTo(-34f, -22f, -33f, -13f); quadTo(-24f, -13f, -21f, -5f)
        quadTo(-11f, -7f, 0f, 0f); close()
    }
    private val bonePath = Path().apply {
        moveTo(0f, 0f); cubicTo(-4f, -16f, -16f, -34f, -30f, -42f); quadTo(-40f, -46f, -47f, -38f)
    }
    private val eggPath = Path().apply {
        moveTo(0f, -100f); cubicTo(22f, -100f, 38f, -62f, 38f, -38f); cubicTo(38f, -14f, 22f, 0f, 0f, 0f)
        cubicTo(-22f, 0f, -38f, -14f, -38f, -38f); cubicTo(-38f, -62f, -22f, -100f, 0f, -100f); close()
    }
    private val tx = FloatArray(TAIL_N + 1)
    private val ty = FloatArray(TAIL_N + 1)
    private val ta = FloatArray(TAIL_N + 1)

    private fun radial(cx: Float, cy: Float, r: Float, a: Int, b: Int, c: Int, mid: Float = 0.5f) {
        fill.shader = RadialGradient(cx, cy, max(r, 0.1f), intArrayOf(a, b, c), floatArrayOf(0f, mid, 1f), Shader.TileMode.CLAMP)
    }

    private fun headPath(stage: Stage, b: Build): Path {
        return headPaths.getOrPut(stage) {
            val p = Path(); p.addCircle(0f, 0f, b.headR, Path.Direction.CW)
            val s = Path(); s.addOval(b.snoutX - b.snoutRx, b.snoutY - b.snoutRy, b.snoutX + b.snoutRx, b.snoutY + b.snoutRy, Path.Direction.CW)
            p.op(s, Path.Op.UNION)
            p
        }
    }

    private fun fillOutline(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, fillC: Int, lineC: Int) {
        fill.color = fillC; c.oval(cx, cy, rx, ry, fill)
        line.color = lineC; c.oval(cx, cy, rx, ry, line)
    }

    private fun spike(c: Canvas, px: Float, py: Float, nx: Float, ny: Float, size: Float, col: DragonColors) {
        val tX = -ny; val tY = nx
        val p = path; p.reset()
        p.moveTo(px + tX * size * 0.6f - nx, py + tY * size * 0.6f - ny)
        p.quadTo(px + nx * size * 0.5f + tX * size * 0.35f, py + ny * size * 0.5f + tY * size * 0.35f, px + nx * size * 1.3f, py + ny * size * 1.3f)
        p.quadTo(px + nx * size * 0.4f - tX * size * 0.2f, py + ny * size * 0.4f - tY * size * 0.2f, px - tX * size * 0.6f - nx, py - tY * size * 0.6f - ny)
        p.close()
        fill.color = col.crest; c.drawPath(p, fill)
        line.color = col.outline; c.drawPath(p, line)
    }

    private fun drawWing(c: Canvas, ax: Float, ay: Float, sc: Float, ang: Float, memb: Int, col: DragonColors) {
        if (sc <= 0.01f) return
        c.save(); c.translate(ax, ay); c.rotate(ang); c.scale(sc, sc)
        fill.color = memb; c.drawPath(wingPath, fill)
        line.strokeWidth = OW * 0.75f / sc; line.color = col.vein
        c.drawLine(-30f, -42f, -41f, -25f, line); c.drawLine(-27f, -39f, -33f, -14f, line); c.drawLine(-20f, -32f, -21f, -6f, line)
        line.strokeWidth = OW / sc; line.color = col.outline; c.drawPath(wingPath, line)
        line.strokeWidth = 3.4f / max(sc, 0.6f); line.color = col.shade; c.drawPath(bonePath, line)
        line.strokeWidth = OW
        c.restore()
    }

    private fun drawLeg(
        c: Canvas, hipX: Float, hipY: Float, footX: Float, footY: Float,
        thighR: Float, footRx: Float, footRy: Float, fillC: Int, col: DragonColors
    ) {
        fillOutline(c, footX, footY, footRx, footRy, fillC, col.outline)
        line.color = col.outline; line.strokeWidth = thighR * 1.05f + OW * 2f
        c.drawLine(hipX, hipY, footX - footRx * 0.2f, footY - footRy * 0.4f, line)
        line.color = fillC; line.strokeWidth = thighR * 1.05f
        c.drawLine(hipX, hipY, footX - footRx * 0.2f, footY - footRy * 0.4f, line)
        radial(hipX - thighR * 0.35f, hipY - thighR * 0.45f, thighR * 1.5f,
            lerpColor(fillC, col.light, 0.55f), fillC, lerpColor(fillC, col.shade, 0.5f), 0.55f)
        c.oval(hipX, hipY, thighR, thighR * 1.08f, fill); fill.shader = null
        line.color = col.outline; line.strokeWidth = OW * 0.85f
        c.oval(hipX, hipY, thighR, thighR * 1.08f, line)
        line.strokeWidth = OW
        fill.color = col.horn
        for (i in 0..2) c.drawCircle(footX + footRx * (0.45f + i * 0.2f), footY + footRy * (0.15f - i * 0.12f), 1.35f, fill)
    }

    fun draw(c: Canvas, cx: Float, feetY: Float, sizePx: Float, face: Float, stage: Stage, p: Pose) {
        val b = build(stage)
        val col = base.tinted(p.night, b.elder)
        val sl = p.sleep
        val k = sizePx / 100f
        c.save()
        c.translate(cx, feetY)
        val f = if (abs(face) >= 0.12f) face else if (face >= 0f) 0.12f else -0.12f
        c.scale(k * f * (1f + p.squash * 0.5f), k * (1f - p.squash))
        if (p.wiggle > 0.01f) c.rotate(sin(p.t * 38f) * 5f * p.wiggle)
        line.strokeWidth = OW
        // deslocamento para centralizar o volume (a cauda puxa para trás)
        c.translate(4f, 0f)

        val bob = -abs(sin(p.walkPhase)) * 2.4f * p.walk
        val br = p.breath
        val bodyCy = b.bodyCy + bob + sl * 4f + br * 0.25f
        val bRx = b.bodyRx * (1f + br * 0.012f)
        val bRy = b.bodyRy * (1f + br * 0.02f) * (1f - sl * 0.06f)
        val hx = lerp(b.headX, b.headX + 7f, sl * 0.9f)
        val hy = lerp(b.headY + bob * 1.2f + br * 0.8f, -b.headR * 0.92f - 1f, sl * 0.9f)
        val tilt = p.headTilt + sl * 0.1f

        // asa distante
        var wingAng = -8f + sin(p.wingPhase) * (3f + 26f * p.flap) + br * 2f
        wingAng = lerp(wingAng, -38f, sl)
        val wsc = b.wing * lerp(1f, 0.78f, sl)
        val ax = -4f
        val ay = bodyCy - bRy * 0.72f
        drawWing(c, ax + 6f, ay - 2f, wsc * 0.92f, wingAng - 12f - sin(p.wingPhase + 0.4f) * 4f * p.flap,
            lerpColor(col.membrane, col.shade, 0.35f), col)

        // cauda
        val n = TAIL_N
        val seg = 54f * b.tail / n
        var ang = lerp((PI - 0.15).toFloat(), (PI + 0.05).toFloat(), sl)
        val curl = lerp(0.075f, 0.055f, sl)
        tx[0] = -bRx * 0.72f; ty[0] = bodyCy + bRy * 0.42f
        val swayK = (1f + p.walk * 0.6f + p.flap * 0.5f) * (1f - sl * 0.7f)
        for (i in 1..n) {
            val u = i.toFloat() / n
            ang += curl + sin(p.t * 1.7f - i * 0.33f) * 0.035f * (0.4f + u) * swayK
            tx[i] = tx[i - 1] + cos(ang) * seg; ty[i] = ty[i - 1] + sin(ang) * seg; ta[i] = ang
        }
        ta[0] = ta[1]
        var i = 4
        while (i < n - 5) {
            val u = i.toFloat() / n
            val w = lerp(b.tailW, 2.2f, u)
            val nx = -sin(ta[i]); val ny = cos(ta[i])
            spike(c, tx[i] + nx * w * 0.8f, ty[i] + ny * w * 0.8f, nx, ny, 4.6f * b.crest * (1f - u * 0.5f), col)
            i += 4
        }
        val tp = path; tp.reset()
        for (j in 0..n) {
            val w = lerp(b.tailW, 2.2f, j.toFloat() / n)
            val nx = -sin(ta[j]); val ny = cos(ta[j])
            if (j == 0) tp.moveTo(tx[j] + nx * w, ty[j] + ny * w) else tp.lineTo(tx[j] + nx * w, ty[j] + ny * w)
        }
        for (j in n downTo 0) {
            val w = lerp(b.tailW, 2.2f, j.toFloat() / n)
            val nx = -sin(ta[j]); val ny = cos(ta[j])
            tp.lineTo(tx[j] - nx * w, ty[j] - ny * w)
        }
        tp.close()
        fill.color = col.body; c.drawPath(tp, fill); line.color = col.outline; c.drawPath(tp, line)
        c.save(); c.translate(tx[n], ty[n]); c.rotate(Math.toDegrees(ta[n].toDouble()).toFloat())
        val ts = 0.6f + 0.4f * b.tail; c.scale(ts, ts)
        tp.reset(); tp.moveTo(-2f, 0f); tp.quadTo(5f, -9f, 15f, 0f); tp.quadTo(5f, 9f, -2f, 0f); tp.close()
        fill.color = col.crest; c.drawPath(tp, fill); line.strokeWidth = OW / ts; c.drawPath(tp, line); line.strokeWidth = OW
        c.restore()

        // pernas do lado distante
        val legS = b.leg
        val shadeLeg = lerpColor(col.body, col.shade, 0.55f)
        val pi = PI.toFloat()
        run {
            val ph1 = p.walkPhase + pi
            val ph2 = p.walkPhase
            val fbx = -3f + cos(ph1) * 6f * p.walk
            val fbl = max(0f, sin(ph1)) * 5f * p.walk
            val ffx = 24f + cos(ph2) * 6f * p.walk
            val ffl = max(0f, sin(ph2)) * 5f * p.walk
            drawLeg(c, -6f, bodyCy + bRy * 0.45f, fbx, lerp(-4.5f - fbl, bodyCy + bRy * 0.5f, sl * 0.85f), 10f * legS, 8.5f, 4.4f, shadeLeg, col)
            drawLeg(c, 19f, bodyCy + bRy * 0.6f, ffx + sl * 6f, -4.5f - ffl, 6.8f * legS, 7.5f, 4f, shadeLeg, col)
        }

        // espinhos do corpo
        for (a in intArrayOf(200, 216, 232, 248, 264)) {
            val r = Math.toRadians(a.toDouble()).toFloat()
            val px = bRx * cos(r); val py = bodyCy + bRy * sin(r)
            var nx = cos(r) / bRx; var ny = sin(r) / bRy
            val d = hypot(nx, ny); nx /= d; ny /= d
            spike(c, px - nx, py - ny, nx, ny, 5.5f * b.crest, col)
        }
        // pescoço
        val n0x = bRx * 0.35f; val n0y = bodyCy - bRy * 0.55f
        val n1x = hx - 2f; val n1y = hy + b.headR * 0.45f
        var dx = n1x - n0x; var dy = n1y - n0y
        val dl = max(0.001f, hypot(dx, dy)); dx /= dl; dy /= dl
        val nbx = dy; val nby = -dx
        for (u in floatArrayOf(0.35f, 0.72f)) {
            spike(c, n0x + dx * dl * u + nbx * b.neckW * 0.42f, n0y + dy * dl * u + nby * b.neckW * 0.42f, nbx, nby, 5f * b.crest, col)
        }

        // asa próxima (atrás do corpo)
        drawWing(c, ax, ay, wsc, wingAng, col.membrane, col)

        line.color = col.outline; line.strokeWidth = b.neckW + OW * 2f
        c.drawLine(n0x, n0y, n1x, n1y, line)
        line.strokeWidth = OW
        // corpo
        radial(-bRx * 0.35f, bodyCy - bRy * 0.45f, bRx * 1.55f, col.light, col.body, col.shade)
        c.oval(0f, bodyCy, bRx, bRy, fill); fill.shader = null
        line.color = col.outline; c.oval(0f, bodyCy, bRx, bRy, line)
        line.color = col.body; line.strokeWidth = b.neckW; c.drawLine(n0x, n0y, n1x, n1y, line)
        line.color = col.belly; line.strokeWidth = b.neckW * 0.42f
        c.drawLine(bRx * 0.58f, bodyCy - bRy * 0.3f, hx + 5f, hy + b.headR * 0.55f, line)
        line.strokeWidth = OW
        // barriga
        val bcx = bRx * 0.3f; val bcy = bodyCy + bRy * 0.12f; val brx = bRx * 0.58f; val bry = bRy * 0.78f
        fill.color = col.belly; c.oval(bcx, bcy, brx, bry, fill)
        line.color = col.bellyLine; line.strokeWidth = OW * 0.8f
        for (j in 1..4) {
            val yy = bcy - bry + j * bry * 2f / 5f
            val dyn = (yy - bcy) / bry
            val hw = brx * sqrt(max(0f, 1f - dyn * dyn)) * 0.82f
            tp.reset(); tp.moveTo(bcx - hw, yy); tp.quadTo(bcx, yy + 2.2f, bcx + hw, yy)
            c.drawPath(tp, line)
        }
        line.strokeWidth = OW

        // pernas próximas
        run {
            val ph1 = p.walkPhase
            val ph2 = p.walkPhase + pi
            val nbx2 = -10f + cos(ph1) * 6f * p.walk
            val nbl = max(0f, sin(ph1)) * 5f * p.walk
            val nfx = 17f + cos(ph2) * 6f * p.walk
            val nfl = max(0f, sin(ph2)) * 5f * p.walk
            drawLeg(c, -14f, bodyCy + bRy * 0.36f, nbx2, lerp(-3.8f - nbl, bodyCy + bRy * 0.55f, sl * 0.85f), 11f * legS, 9.5f, 4.8f, col.body, col)
            drawLeg(c, 12f, bodyCy + bRy * 0.62f, nfx + sl * 8f, -3.8f - nfl, 6.8f * legS, 8f, 4.3f, col.body, col)
        }

        // cabeça
        c.save(); c.translate(hx, hy); c.rotate(Math.toDegrees(tilt.toDouble()).toFloat())
        val r = b.headR
        for (a in intArrayOf(200, 222, 244)) {
            val rr = Math.toRadians(a.toDouble()).toFloat()
            spike(c, r * cos(rr) * 0.97f, r * sin(rr) * 0.97f, cos(rr), sin(rr), 5f * b.crest, col)
        }
        horn(c, -r * 0.02f, -r * 0.93f, b.horn * 0.85f, lerpColor(col.horn, col.hornLine, 0.35f), col)
        horn(c, -r * 0.34f, -r * 0.86f, b.horn, col.horn, col)
        val hp = headPath(stage, b)
        radial(-r * 0.3f, -r * 0.45f, r * 1.7f, col.light, col.body, col.shade)
        c.drawPath(hp, fill); fill.shader = null
        line.color = col.outline; c.drawPath(hp, line)
        // orelha
        c.save(); c.translate(-r * 0.8f, r * 0.02f)
        val happy = p.expr == Expr.JOY || p.expr == Expr.BLISS || p.expr == Expr.LOVE
        c.rotate(sin(p.t * 2.1f) * 4f + (if (happy) 6f else 0f) - (if (p.expr == Expr.SAD) 10f else 0f))
        val fs = 0.5f + 0.18f * b.horn; c.scale(fs, fs)
        tp.reset(); tp.moveTo(3f, -6f); tp.quadTo(-7f, -9f, -18f, -15f); tp.quadTo(-13f, -6f, -15f, 1f)
        tp.quadTo(-8f, 1f, -10f, 6f); tp.quadTo(-3f, 4f, 3f, 4f); tp.close()
        fill.color = col.membrane; c.drawPath(tp, fill)
        line.color = col.outline; line.strokeWidth = OW / fs; c.drawPath(tp, line)
        line.color = col.vein; line.strokeWidth = OW * 0.7f / fs
        c.drawLine(0f, -1f, -14f, -10f, line); c.drawLine(0f, 1f, -10f, 1f, line)
        line.strokeWidth = OW
        c.restore()
        face(c, b, col, p)
        c.restore()
        c.restore()
    }

    private fun horn(c: Canvas, bx: Float, by: Float, h: Float, hc: Int, col: DragonColors) {
        val p = path; p.reset()
        val w = min(1f, 0.55f + h * 0.45f) * 4f
        p.moveTo(bx + w, by + 2f)
        p.quadTo(bx - 1f * h, by - 17f * h, bx - 21f * h, by - 13f * h)
        p.quadTo(bx - 11f * h, by - 3f * h, bx - w, by + 2f)
        p.close()
        fill.color = hc; c.drawPath(p, fill)
        line.color = col.outline; c.drawPath(p, line)
    }

    private fun eye(c: Canvas, ex: Float, ey: Float, rx: Float, ry: Float, o: Float, col: DragonColors, lx: Float, ly: Float, lid: Int) {
        fill.color = col.pupil; c.oval(ex, ey, rx, ry, fill)
        fill.shader = LinearGradient(0f, ey - ry, 0f, ey + ry, col.iris, col.irisDark, Shader.TileMode.CLAMP)
        c.oval(ex + lx * rx * 0.1f, ey + ly * ry * 0.08f, rx * 0.84f, ry * 0.87f, fill); fill.shader = null
        val px = ex + lx * rx * 0.22f; val py = ey + ly * ry * 0.16f + ry * 0.06f
        fill.color = col.pupil; c.oval(px, py, rx * 0.44f, ry * 0.5f, fill)
        fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.95f)
        c.drawCircle(px - rx * 0.3f, py - ry * 0.38f, rx * 0.3f, fill)
        c.drawCircle(px + rx * 0.32f, py + ry * 0.3f, rx * 0.13f, fill)
        if (o < 0.98f) {
            val yl = ey - ry + 2f * ry * (1f - o)
            c.save(); c.clipRect(ex - rx - 2f, ey - ry - 2f, ex + rx + 2f, yl)
            fill.color = lid; c.oval(ex, ey, rx + 0.8f, ry + 0.8f, fill)
            c.restore()
            val q = (yl - ey) / ry
            val hw = rx * sqrt(max(0f, 1f - q * q))
            line.color = col.pupil; line.strokeWidth = 1.8f
            c.drawLine(ex - hw, yl, ex + hw, yl, line); line.strokeWidth = OW
        }
    }

    private fun arcEye(c: Canvas, ex: Float, ey: Float, rx: Float, ry: Float, happy: Boolean, col: DragonColors) {
        val p = path; p.reset()
        if (happy) { p.moveTo(ex - rx, ey + ry * 0.15f); p.quadTo(ex, ey - ry * 0.95f, ex + rx, ey + ry * 0.15f) }
        else { p.moveTo(ex - rx, ey); p.quadTo(ex, ey + ry * 0.6f, ex + rx, ey) }
        line.color = col.pupil; line.strokeWidth = 2.3f
        c.drawPath(p, line); line.strokeWidth = OW
    }

    private fun face(c: Canvas, b: Build, col: DragonColors, p: Pose) {
        val e = p.expr
        val es = b.eye * (if (e == Expr.SURPRISED) 1.1f else 1f)
        val erx = 6.6f * es; val ery = 8.6f * es
        val frx = 3.6f * es; val fry = 7.8f * es
        val ex = b.eyeX; val ey = b.eyeY
        val fx = b.eyeX + 15.5f * b.eye.toDouble().pow(0.3).toFloat(); val fy = b.eyeY - 1.5f
        val blushA = if (e == Expr.LOVE || e == Expr.BLISS) 0.8f else 0.42f
        fill.color = withAlpha(col.blush, blushA)
        c.oval(ex + 1f, ey + ery + 4.5f, 6f * b.eye, 3.2f * b.eye, fill)
        val lid = lerpColor(col.light, col.body, 0.55f)
        when (e) {
            Expr.BLISS, Expr.LOVE -> {
                arcEye(c, ex, ey, erx * 0.9f, ery * 0.6f, true, col)
                arcEye(c, fx, fy, frx * 0.9f, fry * 0.55f, true, col)
            }
            Expr.SLEEP -> {
                arcEye(c, ex, ey + 1f, erx * 0.9f, ery * 0.6f, false, col)
                arcEye(c, fx, fy + 1f, frx * 0.9f, fry * 0.55f, false, col)
            }
            else -> {
                var o = 1f - p.blink
                when (e) {
                    Expr.SLEEPY -> o *= 0.42f
                    Expr.ANNOYED -> o *= 0.5f
                    Expr.SAD -> o *= 0.82f
                    else -> {}
                }
                if (b.elder) o *= 0.85f
                if (o < 0.12f) {
                    arcEye(c, ex, ey + 1f, erx * 0.9f, ery * 0.5f, false, col)
                    arcEye(c, fx, fy + 1f, frx * 0.9f, fry * 0.45f, false, col)
                } else {
                    eye(c, fx, fy, frx, fry, o, col, p.lookX, p.lookY, lid)
                    eye(c, ex, ey, erx, ery, o, col, p.lookX, p.lookY, lid)
                }
            }
        }
        // sobrancelhas
        if (b.elder) {
            line.color = lerpColor(col.light, 0xFFFFFFFF.toInt(), 0.5f); line.strokeWidth = 3.2f
            c.drawLine(ex - erx * 0.9f, ey - ery - 3f, ex + erx * 0.8f, ey - ery - 4.5f, line)
            line.color = col.outline; line.strokeWidth = 0.9f
            c.drawLine(ex - erx * 0.9f, ey - ery - 1.6f, ex + erx * 0.8f, ey - ery - 3.1f, line)
        }
        if (e == Expr.SAD) {
            line.color = col.outline; line.strokeWidth = 1.8f
            c.drawLine(ex - erx * 0.8f, ey - ery - 2.5f, ex + erx * 0.7f, ey - ery - 6.5f, line)
        } else if (e == Expr.ANNOYED) {
            line.color = col.outline; line.strokeWidth = 1.8f
            c.drawLine(ex - erx * 0.8f, ey - ery - 6f, ex + erx * 0.7f, ey - ery - 2f, line)
        }
        line.strokeWidth = OW
        // narinas
        fill.color = col.outline
        c.oval(b.snoutX + b.snoutRx * 0.72f, b.snoutY - b.snoutRy * 0.3f, 1.5f, 1.1f, fill)
        c.oval(b.snoutX + b.snoutRx * 0.45f, b.snoutY - b.snoutRy * 0.42f, 1.1f, 0.85f, fill)
        // boca
        val m0x = b.snoutX - b.snoutRx * 0.45f; val m0y = b.snoutY + b.snoutRy * 0.5f
        val m1x = b.snoutX + b.snoutRx * 0.8f; val m1y = b.snoutY + b.snoutRy * 0.28f
        val mx = (m0x + m1x) / 2f; val my = (m0y + m1y) / 2f
        val pth = path; pth.reset()
        line.color = col.outline; line.strokeWidth = 1.7f
        fun openMouth(depth: Float) {
            pth.reset(); pth.moveTo(m0x, m0y); pth.quadTo(mx, my + depth * 1.9f, m1x, m1y)
            pth.quadTo(mx, my + depth * 0.25f, m0x, m0y); pth.close()
            fill.color = col.mouth; c.drawPath(pth, fill); c.drawPath(pth, line)
        }
        when (e) {
            Expr.JOY, Expr.BLISS, Expr.LOVE -> openMouth(5.5f)
            Expr.CHEW -> if (sin(p.t * 14f) > 0f) openMouth(3.2f) else {
                pth.moveTo(m0x, m0y); pth.quadTo(mx, my + 1.5f, m1x, m1y); c.drawPath(pth, line)
            }
            Expr.SURPRISED -> {
                fill.color = col.mouth; c.oval(mx + 2f, my + 1.5f, 2.8f, 3.4f, fill); c.oval(mx + 2f, my + 1.5f, 2.8f, 3.4f, line)
            }
            Expr.ANNOYED -> {
                pth.moveTo(m0x, m0y); pth.quadTo(m0x + (m1x - m0x) * 0.25f, my - 1.8f, mx, my)
                pth.quadTo(m0x + (m1x - m0x) * 0.75f, my + 1.8f, m1x, m1y); c.drawPath(pth, line)
            }
            else -> {
                val d = when (e) {
                    Expr.CALM -> 3.8f; Expr.NEUTRAL -> 0.8f; Expr.SAD -> -2.4f
                    Expr.SLEEP -> 1.6f; Expr.SLEEPY -> 0.6f; else -> 2f
                }
                pth.moveTo(m0x, m0y); pth.quadTo(mx, my + d, m1x, m1y); c.drawPath(pth, line)
                if (d > 2f) {
                    pth.reset(); pth.moveTo(m0x + 1.5f, m0y + 1.2f); pth.quadTo(m0x - 1.5f, m0y + 0.5f, m0x - 2f, m0y - 2f)
                    c.drawPath(pth, line)
                }
            }
        }
        line.strokeWidth = OW
        if (b.elder) {
            val chx = b.snoutX - 3f; val chy = b.snoutY + b.snoutRy * 0.92f
            val sw = sin(p.t * 1.3f) * 1.5f
            pth.reset(); pth.moveTo(chx - 7f, chy - 2f)
            pth.quadTo(chx - 7f, chy + 7f, chx - 3f + sw, chy + 15f)
            pth.quadTo(chx + 1f, chy + 6f, chx + 5f, chy - 1f); pth.close()
            fill.color = lerpColor(col.light, 0xFFFFFFFF.toInt(), 0.6f); c.drawPath(pth, fill)
            line.color = col.bellyLine; c.drawPath(pth, line)
            line.color = lerpColor(col.outline, col.light, 0.4f); line.strokeWidth = 1.2f
            val wx = b.snoutX + b.snoutRx * 0.55f; val wy = b.snoutY + 1f
            pth.reset(); pth.moveTo(wx, wy); pth.cubicTo(wx + 10f, wy + 2f, wx + 16f, wy + 10f + sw * 2f, wx + 22f, wy + 8f + sw * 3f)
            c.drawPath(pth, line)
            line.strokeWidth = OW
        }
    }

    // ------------------------------------------------------------------ ovo
    fun drawEgg(c: Canvas, cx: Float, feetY: Float, sizePx: Float, wobble: Float, progress: Float, glow: Float, night: Float) {
        val col = base.tinted(night, false)
        val k = sizePx / 100f
        if (glow > 0.01f) {
            c.save(); c.translate(cx, feetY - sizePx * 0.45f)
            fill.shader = RadialGradient(0f, 0f, sizePx * 0.9f, withAlpha(col.iris, 0.35f * glow), withAlpha(col.iris, 0f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, sizePx * 0.9f, fill); fill.shader = null
            c.restore()
        }
        c.save(); c.translate(cx, feetY); c.rotate(wobble); c.scale(k, k)
        line.strokeWidth = OW
        radial(-12f, -66f, 85f, col.light, col.body, col.shade, 0.55f)
        c.drawPath(eggPath, fill); fill.shader = null
        fill.color = withAlpha(col.speck, 0.75f)
        var i = 0
        while (i < EGG_SPECKS.size) {
            c.oval(EGG_SPECKS[i], EGG_SPECKS[i + 1], EGG_SPECKS[i + 2], EGG_SPECKS[i + 2] * 0.8f, fill); i += 3
        }
        line.color = col.outline; c.drawPath(eggPath, line)
        c.save(); c.translate(-15f, -68f); c.rotate(-22f)
        fill.color = withAlpha(0xFFFFFFFF.toInt(), 0.55f); c.oval(0f, 0f, 6.5f, 13f, fill)
        c.restore()
        val crack = max(0f, (progress - 0.72f) / 0.28f)
        if (crack > 0f) {
            line.color = col.outline; line.strokeWidth = 1.8f
            val segs = min(4, ceil(crack * 4f).toInt())
            var px = CRACK[0]; var py = CRACK[1]
            for (s in 1..segs) { c.drawLine(px, py, CRACK[s * 2], CRACK[s * 2 + 1], line); px = CRACK[s * 2]; py = CRACK[s * 2 + 1] }
            px = CRACK[0]; py = CRACK[1]
            for (s in 5 until 5 + segs) { c.drawLine(px, py, CRACK[s * 2], CRACK[s * 2 + 1], line); px = CRACK[s * 2]; py = CRACK[s * 2 + 1] }
            line.strokeWidth = OW
        }
        c.restore()
    }

    fun drawNest(c: Canvas, cx: Float, feetY: Float, widthPx: Float, night: Float, front: Boolean) {
        val k = widthPx / 100f
        val nn = DragonColors.NIGHT
        val twig = lerpColor(hex("#CDB08F"), nn, night * 0.42f)
        val twigD = lerpColor(hex("#B39272"), nn, night * 0.42f)
        val inner = lerpColor(hex("#A88B6E"), nn, night * 0.42f)
        val leaf = lerpColor(hex("#AFCDA4"), nn, night * 0.42f)
        c.save(); c.translate(cx, feetY); c.scale(k, k)
        if (!front) {
            fill.color = twigD; c.oval(0f, -2f, 50f, 14f, fill)
            fill.color = inner; c.oval(0f, -3f, 40f, 9f, fill)
        } else {
            val p = path; p.reset()
            p.moveTo(-50f, -3f); p.quadTo(-48f, 13f, 0f, 14f); p.quadTo(48f, 13f, 50f, -3f)
            p.quadTo(30f, 6f, 0f, 6f); p.quadTo(-30f, 6f, -50f, -3f); p.close()
            fill.color = twig; c.drawPath(p, fill)
            line.color = twigD; line.strokeWidth = 1.6f
            for (i in -4..4) {
                val x = i * 10f
                c.drawLine(x - 7f, 5f - abs(i) * 0.6f, x + 7f, 9f - abs(i) * 0.7f, line)
            }
            line.strokeWidth = OW
            val leaves = floatArrayOf(-38f, 3f, -30f, 34f, 4f, 25f, -8f, 11f, 5f)
            var j = 0
            while (j < leaves.size) {
                c.save(); c.translate(leaves[j], leaves[j + 1]); c.rotate(leaves[j + 2])
                fill.color = leaf; c.oval(0f, 0f, 7f, 3.2f, fill); c.restore()
                j += 3
            }
        }
        c.restore()
    }
}
