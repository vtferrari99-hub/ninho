package br.com.coffeelabs.ninho

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Floresta em tons pastel com céu que acompanha o relógio do celular. */
class ForestRenderer {

    private class Key(val h: Float, val top: Int, val bot: Int, val night: Float)

    private val keys = arrayOf(
        Key(0f, hex("#39416A"), hex("#5D6290"), 1f),
        Key(5f, hex("#3F4870"), hex("#6A6A95"), 1f),
        Key(6f, hex("#E3B7C6"), hex("#F8DCC4"), 0.35f),
        Key(7.5f, hex("#BCD7EE"), hex("#F2E9D6"), 0f),
        Key(12f, hex("#B4D6F0"), hex("#E3F1EC"), 0f),
        Key(17f, hex("#C2D3EC"), hex("#F3E3D0"), 0f),
        Key(18.5f, hex("#D7AFC8"), hex("#F5CEB6"), 0.25f),
        Key(19.5f, hex("#6E6A9A"), hex("#B39AB8"), 0.7f),
        Key(21f, hex("#3C4470"), hex("#5F6492"), 1f),
        Key(24f, hex("#39416A"), hex("#5D6290"), 1f)
    )

    var skyTop = 0; private set
    var skyBot = 0; private set
    var night = 0f; private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private var w = 0f; private var h = 0f; private var hy = 0f
    private var backY = 0f; private var frontY = 0f; private var dp = 1f

    private val stars = ArrayList<FloatArray>()
    private val clouds = ArrayList<FloatArray>()
    private val far = ArrayList<FloatArray>()
    private val mid = ArrayList<FloatArray>()
    private val flowers = ArrayList<FloatArray>()
    private val tufts = ArrayList<FloatArray>()
    private val near = ArrayList<FloatArray>()
    private val flies = ArrayList<FloatArray>()
    private val leaves = ArrayList<FloatArray>()
    private var hill1 = Path()
    private var hill2 = Path()
    private val flowerColors = intArrayOf(hex("#F4D3DC"), hex("#FFF1C4"), hex("#DCD3F2"), hex("#F7DCCB"), hex("#FFFFFF"))

    fun resize(w: Float, h: Float, horizonY: Float, backY: Float, frontY: Float, dp: Float) {
        this.w = w; this.h = h; this.hy = horizonY; this.backY = backY; this.frontY = frontY; this.dp = dp
        val rnd = Random(7)
        fun r() = rnd.nextFloat()
        stars.clear(); repeat(46) { stars.add(floatArrayOf(r() * w, r() * horizonY * 0.85f, (0.6f + r() * 1.1f) * dp, r() * 6.28f)) }
        clouds.clear(); repeat(4) { clouds.add(floatArrayOf(r(), horizonY * (0.18f + r() * 0.4f), (40f + r() * 40f) * dp, 0.004f + r() * 0.006f)) }
        hill1 = hills(rnd, horizonY - 0.03f * h, 0.05f * h, 3)
        hill2 = hills(rnd, horizonY + 0.005f * h, 0.035f * h, 5)
        far.clear()
        var x = -20f * dp
        while (x < w + 20f * dp) {
            far.add(floatArrayOf(x, horizonY + 0.02f * h + r() * 0.01f * h, h * (0.07f + r() * 0.05f)))
            x += (14f + r() * 16f) * dp
        }
        mid.clear()
        x = -30f * dp
        while (x < w + 30f * dp) {
            mid.add(floatArrayOf(x, backY - 0.035f * h + r() * 0.012f * h, h * (0.1f + r() * 0.05f), r() * 6.28f, if (r() < 0.4f) 1f else 0f))
            x += (34f + r() * 30f) * dp
        }
        fun inClearing(px: Float, py: Float): Boolean {
            val ex = (px - w / 2f) / (w * 0.46f)
            val ey = (py - (backY + frontY) / 2f) / ((frontY - backY) * 0.95f)
            return ex * ex + ey * ey < 1f
        }
        flowers.clear()
        repeat(70) {
            val fx = r() * w
            val fy = backY - 0.03f * h + r() * (h - backY + 0.03f * h)
            if (!inClearing(fx, fy)) flowers.add(floatArrayOf(fx, fy, (2.2f + r() * 1.6f) * dp, rnd.nextInt(flowerColors.size).toFloat(), r() * 6.28f))
        }
        tufts.clear()
        repeat(30) {
            val tx = r() * w; val ty = backY + r() * (h - backY)
            if (!inClearing(tx, ty)) tufts.add(floatArrayOf(tx, ty, (6f + r() * 6f) * dp))
        }
        near.clear()
        near.add(floatArrayOf(-0.07f * w, backY + 0.03f * h, 0.34f * h, 1f))
        near.add(floatArrayOf(1.07f * w, backY + 0.025f * h, 0.31f * h, -1f))
        flies.clear(); repeat(16) { flies.add(floatArrayOf(r(), r(), r() * 6.28f, 0.5f + r())) }
        leaves.clear(); repeat(6) { leaves.add(floatArrayOf(r(), r(), r() * 6.28f, 0.6f + r() * 0.6f)) }
    }

    private fun hills(rnd: Random, base: Float, amp: Float, n: Int): Path {
        val p = Path()
        p.moveTo(0f, h); p.lineTo(0f, base)
        val step = w / n
        for (i in 0 until n) {
            val x0 = i * step
            p.quadTo(x0 + step * 0.5f, base - amp * (0.5f + rnd.nextFloat() * 0.8f), x0 + step, base - amp * rnd.nextFloat() * 0.3f)
        }
        p.lineTo(w, h); p.close()
        return p
    }

    fun updateSky(hour: Float) {
        for (i in 0 until keys.size - 1) {
            val a = keys[i]; val b = keys[i + 1]
            if (hour >= a.h && hour <= b.h) {
                val t = smooth((hour - a.h) / (b.h - a.h))
                skyTop = lerpColor(a.top, b.top, t)
                skyBot = lerpColor(a.bot, b.bot, t)
                night = lerp(a.night, b.night, t)
                return
            }
        }
        skyTop = keys[0].top; skyBot = keys[0].bot; night = 1f
    }

    private fun tint(c: Int, k: Float = 0.55f): Int = lerpColor(c, DragonColors.NIGHT, night * k)

    private fun pine(c: Canvas, x: Float, base: Float, hh: Float, col: Int) {
        val p = path; p.reset()
        val wd = hh * 0.42f
        for (tier in 0..2) {
            val tyy = base - hh * (0.18f + tier * 0.27f)
            val tw = wd * (1f - tier * 0.25f)
            val top = tyy - hh * 0.42f
            p.moveTo(x - tw / 2f, tyy)
            p.quadTo(x - tw * 0.12f, top + hh * 0.08f, x, top)
            p.quadTo(x + tw * 0.12f, top + hh * 0.08f, x + tw / 2f, tyy)
            p.quadTo(x, tyy + hh * 0.05f, x - tw / 2f, tyy)
            p.close()
        }
        fill.color = col; c.drawPath(p, fill)
    }

    private fun roundTree(c: Canvas, x: Float, base: Float, hh: Float, leaf: Int, leafD: Int, trunk: Int, sway: Float) {
        fill.color = trunk
        c.drawRoundRect(x - hh * 0.045f, base - hh * 0.45f, x + hh * 0.045f, base, hh * 0.03f, hh * 0.03f, fill)
        val r = hh * 0.27f
        val ox = sin(sway) * hh * 0.01f
        fill.color = leafD
        c.drawCircle(x - r * 0.75f + ox, base - hh * 0.52f, r * 0.8f, fill)
        c.drawCircle(x + r * 0.75f + ox, base - hh * 0.5f, r * 0.78f, fill)
        fill.color = leaf
        c.drawCircle(x + ox, base - hh * 0.7f, r, fill)
        c.drawCircle(x - r * 0.5f + ox, base - hh * 0.58f, r * 0.72f, fill)
    }

    /** Céu, astros, colinas, árvores e chão (atrás do dragão). */
    fun drawBack(c: Canvas, hour: Float, t: Float) {
        updateSky(hour)
        fill.shader = LinearGradient(0f, 0f, 0f, hy + 0.05f * h, skyTop, skyBot, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        if (night > 0.05f) {
            for (s in stars) {
                val a = night * (0.55f + 0.45f * sin(t * 1.3f + s[3]))
                fill.color = withAlpha(hex("#FFF8E6"), a); c.drawCircle(s[0], s[1], s[2], fill)
            }
        }
        val pi = PI.toFloat()
        if (hour in 5.5f..19f) {
            val u = (hour - 5.5f) / 13.5f
            val sx = w * (0.08f + 0.84f * u); val sy = hy - sin(u * pi) * h * 0.3f + h * 0.02f
            fill.shader = RadialGradient(sx, sy, 60f * dp, withAlpha(hex("#FFF6D8"), 0.8f), withAlpha(hex("#FFF6D8"), 0f), Shader.TileMode.CLAMP)
            c.drawCircle(sx, sy, 60f * dp, fill); fill.shader = null
            fill.color = hex("#FFF3CF"); c.drawCircle(sx, sy, 20f * dp, fill)
        }
        val mh = if (hour >= 18.5f) hour - 18.5f else hour + 5.5f
        if (mh in 0f..12f) {
            val u = mh / 12f
            val mx = w * (0.1f + 0.8f * u); val my = hy - sin(u * pi) * h * 0.3f + h * 0.03f
            fill.shader = RadialGradient(mx, my, 50f * dp, withAlpha(hex("#EEF0FF"), 0.45f), withAlpha(hex("#EEF0FF"), 0f), Shader.TileMode.CLAMP)
            c.drawCircle(mx, my, 50f * dp, fill); fill.shader = null
            fill.color = hex("#F5F2E6"); c.drawCircle(mx, my, 15f * dp, fill)
            fill.color = withAlpha(hex("#DCD8CB"), 0.7f)
            c.drawCircle(mx - 4f * dp, my - 3f * dp, 3.2f * dp, fill); c.drawCircle(mx + 5f * dp, my + 4f * dp, 2.2f * dp, fill)
        }
        for (cl in clouds) {
            val cx = (((cl[0] + t * cl[3]) % 1.3f) - 0.15f) * w
            val cy = cl[1]; val cw = cl[2]
            fill.color = withAlpha(lerpColor(0xFFFFFFFF.toInt(), hex("#8E93B8"), night), 0.55f - night * 0.25f)
            c.drawCircle(cx, cy, cw * 0.32f, fill); c.drawCircle(cx + cw * 0.3f, cy + cw * 0.05f, cw * 0.26f, fill)
            c.drawCircle(cx - cw * 0.3f, cy + cw * 0.07f, cw * 0.22f, fill)
            c.drawRoundRect(cx - cw * 0.5f, cy, cx + cw * 0.52f, cy + cw * 0.22f, cw * 0.11f, cw * 0.11f, fill)
        }
        fill.color = tint(hex("#C6DACB")); c.drawPath(hill1, fill)
        fill.color = tint(hex("#B4CFB6")); c.drawPath(hill2, fill)
        val farC = tint(hex("#A5C4A9"))
        for (f in far) pine(c, f[0], f[1], f[2], farC)
        // chão
        val gTop = backY - 0.045f * h
        fill.shader = LinearGradient(0f, gTop, 0f, h, tint(hex("#C9E0B7")), tint(hex("#AFCF9E")), Shader.TileMode.CLAMP)
        val p = path; p.reset()
        p.moveTo(0f, gTop + 0.01f * h); p.quadTo(w * 0.3f, gTop - 0.012f * h, w * 0.55f, gTop + 0.004f * h)
        p.quadTo(w * 0.8f, gTop + 0.014f * h, w, gTop - 0.004f * h); p.lineTo(w, h); p.lineTo(0f, h); p.close()
        c.drawPath(p, fill); fill.shader = null
        val midPine = tint(hex("#93B89A")); val leafC = tint(hex("#A2C69F")); val leafD = tint(hex("#8DB592")); val trunk = tint(hex("#BFA58E"))
        for (m in mid) {
            if (m[4] > 0.5f) pine(c, m[0], m[1], m[2] * 1.15f, midPine)
            else roundTree(c, m[0], m[1], m[2], leafC, leafD, trunk, t * 0.6f + m[3])
        }
        // árvores laterais (moldura)
        val nLeaf = tint(hex("#8FB794")); val nLeafD = tint(hex("#7EA886")); val nTrunk = tint(hex("#B59A84"))
        for (nt in near) {
            val hh = nt[2]; val side = nt[3]
            c.save(); c.translate(nt[0], nt[1]); c.rotate(sin(t * 0.5f + nt[0]) * 0.6f)
            fill.color = nTrunk
            c.drawRoundRect(-hh * 0.05f, -hh * 0.5f, hh * 0.05f, 0f, hh * 0.04f, hh * 0.04f, fill)
            val r = hh * 0.25f
            fill.color = nLeafD
            c.drawCircle(-r * 0.8f * side, -hh * 0.55f, r * 0.85f, fill)
            c.drawCircle(r * 0.9f * side, -hh * 0.6f, r * 0.8f, fill)
            fill.color = nLeaf
            c.drawCircle(0f, -hh * 0.75f, r, fill)
            c.drawCircle(r * 0.5f * side, -hh * 0.92f, r * 0.7f, fill)
            c.drawCircle(-r * 0.3f * side, -hh * 0.6f, r * 0.75f, fill)
            c.restore()
        }
        // clareira
        val cy = (backY + frontY) / 2f
        val sand = tint(hex("#EBE2C9"))
        fill.shader = RadialGradient(0f, 0f, w * 0.5f, intArrayOf(withAlpha(sand, 0.85f), withAlpha(sand, 0.85f), withAlpha(sand, 0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.translate(w / 2f, cy); c.scale(1f, (frontY - backY) * 1.25f / (w * 0.5f))
        c.drawCircle(0f, 0f, w * 0.5f, fill); c.restore(); fill.shader = null
        val center = tint(hex("#F6E2A6"), 0.5f)
        for (fl in flowers) {
            val fr = fl[2]
            fill.color = tint(flowerColors[fl[3].toInt()], 0.5f)
            for (i in 0..4) {
                val a = fl[4] + i * 1.2566f
                c.drawCircle(fl[0] + cos(a) * fr, fl[1] + sin(a) * fr, fr * 0.75f, fill)
            }
            fill.color = center; c.drawCircle(fl[0], fl[1], fr * 0.55f, fill)
        }
        line.color = tint(hex("#97BE8C")); line.strokeWidth = 2f * dp
        for (tf in tufts) {
            val ts = tf[2]
            for (k in -1..1) {
                p.reset(); p.moveTo(tf[0] + k * ts * 0.3f, tf[1])
                p.quadTo(tf[0] + k * ts * 0.4f, tf[1] - ts * 0.6f, tf[0] + k * ts * 0.75f + sin(t + tf[0]) * ts * 0.08f, tf[1] - ts)
                c.drawPath(p, line)
            }
        }
    }

    /** Folhas caindo de dia e vaga-lumes à noite (na frente de tudo). */
    fun drawFront(c: Canvas, t: Float) {
        val day = 1f - night
        if (day > 0.05f) {
            for (lf in leaves) {
                val sp = lf[3]
                val u = ((t * 0.045f * sp + lf[0]) % 1f)
                val x = (lf[1] * 1.2f - 0.1f) * w + sin(t * 1.1f * sp + lf[2]) * 30f * dp + u * 40f * dp
                val y = hy - 0.1f * h + u * (h * 0.55f)
                val a = day * 0.75f * smooth(u * 6f) * smooth((1f - u) * 4f)
                c.save(); c.translate(x, y); c.rotate(sin(t * 2f * sp + lf[2]) * 50f)
                fill.color = withAlpha(if (lf[2] > 3.14f) hex("#B9D3A6") else hex("#F2CDB4"), a)
                c.oval(0f, 0f, 5f * dp, 2.4f * dp, fill)
                c.restore()
            }
        }
        if (night > 0.05f) {
            for (fy in flies) {
                val sp = fy[3]
                val x = (fy[0] + sin(t * 0.13f * sp + fy[2]) * 0.08f) * w
                val y = hy + (fy[1] * 0.9f - 0.1f) * (h * 0.85f - hy) + cos(t * 0.21f * sp + fy[2] * 2f) * 0.03f * h
                val a = night * (0.4f + 0.6f * max(0f, sin(t * 1.4f * sp + fy[2])))
                fill.shader = RadialGradient(x, y, 14f * dp, withAlpha(hex("#F8EDB4"), 0.55f * a), withAlpha(hex("#F8EDB4"), 0f), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, 14f * dp, fill); fill.shader = null
                fill.color = withAlpha(hex("#FFF7D6"), a); c.drawCircle(x, y, 2.2f * dp, fill)
            }
        }
    }
}
