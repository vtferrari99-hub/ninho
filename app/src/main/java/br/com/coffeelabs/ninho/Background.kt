package br.com.coffeelabs.ninho

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Céu por hora do dia (cores e "quanto é noite"). */
class SkyState(val top: Int, val mid: Int, val hor: Int, val night: Float) {
    companion object {
        private class K(val h: Float, val top: Int, val mid: Int, val hor: Int, val n: Float)
        private val keys = arrayOf(
            K(0f, hex("#0F1A3C"), hex("#1D2B56"), hex("#3A4A78"), 1f), K(5f, hex("#141F45"), hex("#2A3562"), hex("#55598A"), 1f),
            K(6f, hex("#8F9FE0"), hex("#E3C2E2"), hex("#FFD3A6"), 0.25f), K(7.5f, hex("#93AEE8"), hex("#D6C8EC"), hex("#FBDCC6"), 0f),
            K(12f, hex("#7FA9E6"), hex("#BFD3F0"), hex("#F3E4DC"), 0f), K(16.5f, hex("#8BA9E4"), hex("#CFC9EC"), hex("#F8DCC6"), 0f),
            K(18.3f, hex("#8B8FD6"), hex("#E6B4D2"), hex("#FFC79A"), 0.2f), K(19.6f, hex("#333F7C"), hex("#8674A6"), hex("#D79C96"), 0.7f),
            K(21f, hex("#111C40"), hex("#1F2D58"), hex("#3C4B7A"), 1f), K(24f, hex("#0F1A3C"), hex("#1D2B56"), hex("#3A4A78"), 1f)
        )

        fun at(hour: Float): SkyState {
            for (i in 0 until keys.size - 1) {
                val a = keys[i]; val b = keys[i + 1]
                if (hour >= a.h && hour <= b.h) {
                    val t = smooth((hour - a.h) / (b.h - a.h))
                    return SkyState(lerpColor(a.top, b.top, t), lerpColor(a.mid, b.mid, t), lerpColor(a.hor, b.hor, t), lerp(a.n, b.n, t))
                }
            }
            return SkyState(keys[0].top, keys[0].mid, keys[0].hor, 1f)
        }
    }
}

/**
 * Pinta o cenário: Monte Fuji ao amanhecer refletido no lago, com galhos de bordo (momiji)
 * emoldurando a cena. Tudo procedural (Canvas), com desfoques para um ar fotográfico.
 */
object FujiPainter {
    private val NIGHTC = hex("#18244C")
    const val SHORE = 0.47f

    private fun blur(p: Paint, r: Float) { p.maskFilter = if (r > 0f) BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) else null }
    private fun lin(p: Paint, x0: Float, y0: Float, x1: Float, y1: Float, cols: IntArray, pos: FloatArray?) {
        p.alpha = 255; p.shader = LinearGradient(x0, y0, x1, y1, cols, pos, Shader.TileMode.CLAMP)
    }
    private fun rad(p: Paint, cx: Float, cy: Float, r: Float, c0: Int, c1: Int) {
        p.alpha = 255; p.shader = RadialGradient(cx, cy, r, c0, c1, Shader.TileMode.CLAMP)
    }

    fun paint(w: Int, h: Int, hour: Float, dp: Float): Bitmap {
        val sky = SkyState.at(hour)
        val wf = w.toFloat(); val hf = h.toFloat()
        val shoreY = hf * SHORE
        val topH = shoreY.toInt() + 2
        val topBmp = Bitmap.createBitmap(w, topH, Bitmap.Config.ARGB_8888)
        paintTop(Canvas(topBmp), wf, hf, hour, dp, shoreY, sky)
        val small = Bitmap.createScaledBitmap(topBmp, w / 3, topH / 3, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(topBmp, 0f, 0f, null)
        paintWater(c, wf, hf, dp, shoreY, small, sky)
        paintForeground(c, wf, hf, dp, sky.night)
        topBmp.recycle(); small.recycle()
        return out
    }

    /** Versão bem desfocada do fundo, para o vidro fosco do HUD. */
    fun frosted(src: Bitmap): Bitmap {
        val a = Bitmap.createScaledBitmap(src, maxOf(8, src.width / 6), maxOf(8, src.height / 6), true)
        val b = Bitmap.createScaledBitmap(a, maxOf(4, src.width / 28), maxOf(4, src.height / 28), true)
        a.recycle()
        return b
    }

    private fun rad3(p: Paint, cx: Float, cy: Float, r: Float, cols: IntArray, pos: FloatArray) {
        p.alpha = 255; p.shader = RadialGradient(cx, cy, r, cols, pos, Shader.TileMode.CLAMP)
    }

    private fun paintTop(c: Canvas, w: Float, h: Float, hour: Float, dp: Float, shoreY: Float, sky: SkyState) {
        val rnd = Random(21)
        fun r() = rnd.nextFloat()
        val night = sky.night; val day = 1f - night
        val pi = PI.toFloat()
        val F = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val L = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun tn(col: Int, k: Float = 0.74f) = lerpColor(col, NIGHTC, night * k)
        lin(F, 0f, 0f, 0f, shoreY, intArrayOf(sky.top, sky.mid, sky.hor), floatArrayOf(0f, 0.6f, 1f))
        c.drawRect(0f, 0f, w, shoreY + 2f, F); F.shader = null
        // calor do sol à esquerda (atrás da colina)
        rad3(F, -w * 0.05f, shoreY * 0.8f, w * 1.05f, intArrayOf(withAlpha(hex("#FFD58A"), 0.85f * day), withAlpha(hex("#FFC9A0"), 0.35f * day), withAlpha(hex("#FFC9A0"), 0f)), floatArrayOf(0f, 0.45f, 1f))
        c.drawRect(0f, 0f, w, shoreY, F); F.shader = null
        for (i in 0 until 110) {
            val sx = r() * w; val sy = r() * shoreY * 0.85f; val sr = (0.5f + r() * 1.1f) * dp; val sa = 0.3f + r() * 0.7f
            if (night > 0.05f) { F.color = withAlpha(hex("#FFF8E8"), night * sa); c.drawCircle(sx, sy, sr, F) }
        }
        val mh = if (hour >= 18.5f) hour - 18.5f else hour + 5.5f
        if (mh in 0f..12f && night > 0.5f) {
            val u = mh / 12f; val k = (night - 0.5f) * 2f
            val mx = w * (0.14f + 0.25f * u); val my = h * (0.27f - sin(u * pi) * 0.08f)
            rad(F, mx, my, 100f * dp, withAlpha(hex("#DCE6FF"), 0.4f * k), withAlpha(hex("#DCE6FF"), 0f))
            c.drawCircle(mx, my, 100f * dp, F); F.shader = null
            F.color = withAlpha(hex("#FBF7EA"), k); c.drawCircle(mx, my, 20f * dp, F)
        }
        // nuvens em camadas
        val clouds = floatArrayOf(0.12f, 0.215f, 0.5f, 0.012f, 0.8f, 0.3f, 0.25f, 0.42f, 0.01f, 0.7f, 0.05f, 0.28f, 0.5f, 0.014f, 0.85f, 0.22f, 0.3f, 0.3f, 0.008f, 0.6f,
            0.92f, 0.26f, 0.4f, 0.012f, 0.7f, 0.8f, 0.295f, 0.34f, 0.008f, 0.55f, 0.6f, 0.12f, 0.16f, 0.007f, 0.5f, 0.2f, 0.16f, 0.2f, 0.006f, 0.4f)
        blur(F, 9f * dp)
        for (i in 0 until 8) {
            val cx = clouds[i * 5]; val cy = clouds[i * 5 + 1]; val cw = clouds[i * 5 + 2]; val ch = clouds[i * 5 + 3]; val a = clouds[i * 5 + 4]
            F.color = withAlpha(tn(lerpColor(hex("#B9A5D6"), sky.hor, 0.2f)), a * 0.55f * (1f - night * 0.4f))
            c.drawOval(w * (cx - cw / 2f), h * (cy - ch * 0.4f), w * (cx + cw / 2f), h * (cy + ch * 1.4f), F)
            F.color = withAlpha(lerpColor(if (cx < 0.5f) hex("#FFE9C9") else hex("#FBD9E2"), hex("#6D78A8"), night), a * (1f - night * 0.5f))
            c.drawOval(w * (cx - cw * 0.45f), h * (cy - ch), w * (cx + cw * 0.45f), h * (cy + ch * 0.5f), F)
        }
        blur(F, 0f)
        val baseY = shoreY - h * 0.01f
        // crista secundária (direita)
        val r2 = Path(); r2.moveTo(w * 0.45f, baseY); r2.cubicTo(w * 0.7f, baseY - h * 0.02f, w * 0.82f, baseY - h * 0.085f, w * 0.95f, baseY - h * 0.09f)
        r2.cubicTo(w * 1.02f, baseY - h * 0.09f, w * 1.1f, baseY - h * 0.07f, w * 1.2f, baseY - h * 0.075f); r2.lineTo(w * 1.2f, baseY); r2.close()
        lin(F, 0f, baseY - h * 0.09f, 0f, baseY, intArrayOf(tn(hex("#8E84BC")), tn(hex("#C99BB4"))), null); c.drawPath(r2, F); F.shader = null
        // ---- Fuji
        val px = w * 0.5f; val py = h * 0.19f
        val m = Path()
        m.moveTo(-w * 0.75f, baseY)
        m.cubicTo(-w * 0.1f, baseY - h * 0.04f, w * 0.27f, py + h * 0.085f, px - w * 0.06f, py + h * 0.006f)
        val tp = floatArrayOf(-0.04f, -0.001f, -0.022f, 0.003f, -0.005f, -0.002f, 0.012f, -0.004f, 0.03f, -0.001f, 0.05f, 0.004f)
        for (i in 0 until 6) m.lineTo(px + w * tp[i * 2], py + h * tp[i * 2 + 1])
        m.cubicTo(px + w * 0.3f, py + h * 0.085f, w * 1.15f, baseY - h * 0.06f, w * 1.8f, baseY - h * 0.02f)
        m.lineTo(w * 1.8f, baseY); m.close()
        val rockL = tn(lerpColor(hex("#D2724E"), hex("#B77A8E"), 0.15f)); val rockR = tn(hex("#9D6E9C"))
        val low = tn(lerpColor(hex("#E3A0A0"), sky.hor, 0.35f), 0.6f)
        lin(F, px - w * 0.55f, 0f, px + w * 0.6f, 0f, intArrayOf(rockL, lerpColor(rockL, rockR, 0.5f), rockR), floatArrayOf(0f, 0.5f, 1f)); c.drawPath(m, F); F.shader = null
        c.save(); c.clipPath(m)
        lin(F, 0f, py + h * 0.13f, 0f, baseY, intArrayOf(withAlpha(low, 0f), withAlpha(low, 0.85f)), null); c.drawRect(-w, py, w * 2f, baseY + 2f, F); F.shader = null
        val patch = intArrayOf(hex("#E0664A"), hex("#EE8A5A"), hex("#D9566A"), hex("#8F6FA8"), hex("#7C6AA8"))
        blur(F, 7f * dp)
        for (i in 0 until 130) {
            val u = r(); val yy = py + h * (0.13f + 0.14f * r()); val xx = px + (r() - 0.5f) * w * 1.3f
            F.color = withAlpha(tn(patch[rnd.nextInt(5)]), 0.16f + 0.14f * r())
            c.drawOval(xx - (14f + 40f * u) * dp, yy - (3f + 5f * u) * dp, xx + (14f + 40f * u) * dp, yy + (3f + 5f * u) * dp, F)
        }
        blur(F, 0f)
        fun rx(a: Float, d: Float) = px + w * 0.005f + sin(a) * d * 1.9f
        fun ry(a: Float, d: Float) = py + h * 0.008f + (cos(a) * 0.75f + abs(a) * 0.1f) * d
        blur(L, 1.2f * dp)
        for (i in 0 until 120) {
            val a = (r() - 0.5f) * 2.3f; val ln = h * (0.05f + r() * 0.2f); val st = r() * 0.35f
            val d0 = h * 0.02f + ln * st
            L.color = withAlpha(if (i % 3 != 0) tn(hex("#6B4C86")) else tn(hex("#FFD9C0")), 0.07f + r() * 0.1f)
            L.strokeWidth = (0.8f + r() * 2.2f) * dp
            c.drawLine(rx(a, d0), ry(a, d0), rx(a, ln), ry(a, ln), L)
        }
        blur(L, 0f)
        // neve
        val snowL = tn(lerpColor(0xFFFFFFFF.toInt(), hex("#FFF1E0"), day), 0.4f); val snowR = tn(hex("#D9C3E6"), 0.45f)
        val cap = Path(); cap.moveTo(px - w * 0.37f, py + h * 0.115f)
        cap.lineTo(px - w * 0.06f, py - h * 0.01f); cap.lineTo(px + w * 0.06f, py - h * 0.01f); cap.lineTo(px + w * 0.44f, py + h * 0.125f)
        val n = 41
        for (i in 0 until n) {
            val u = 1f - i / (n - 1f)
            val xx = px - w * 0.37f + u * w * 0.81f
            val edge = abs(u - 0.46f) * 2f
            val dip = h * (0.062f + 0.06f * edge) - h * (if (i % 2 == 0) 0f else 0.012f + r() * 0.03f) + h * r() * 0.006f
            cap.lineTo(xx, py + dip)
        }
        cap.close()
        lin(F, px - w * 0.3f, 0f, px + w * 0.35f, 0f, intArrayOf(snowL, lerpColor(snowL, snowR, 0.35f), snowR), floatArrayOf(0f, 0.42f, 1f)); blur(F, 1.2f * dp)
        c.drawPath(cap, F)
        val t = Path()
        for (i in 0 until 70) {
            val a = (r() - 0.5f) * 2.3f; val ln = h * (0.07f + r() * 0.08f); val wd = (2f + r() * 5f) * dp
            val x0 = rx(a, h * 0.05f); val y0 = ry(a, h * 0.05f)
            t.reset(); t.moveTo(x0 - wd, y0); t.lineTo(x0 + wd, y0); t.lineTo(rx(a, ln), ry(a, ln)); t.close(); c.drawPath(t, F)
        }
        F.shader = null; blur(F, 0f)
        blur(L, 2f * dp)
        for (i in 0 until 40) {
            val a = (r() - 0.3f) * 1.8f; val ln = h * (0.02f + r() * 0.07f)
            L.color = withAlpha(tn(hex("#B59CCF")), 0.12f + 0.12f * r()); L.strokeWidth = (1.5f + r() * 3f) * dp
            c.drawLine(rx(a, h * 0.012f), ry(a, h * 0.012f), rx(a, ln), ry(a, ln), L)
        }
        blur(L, 0f)
        lin(F, px - w * 0.6f, 0f, px + w * 0.15f, 0f, intArrayOf(withAlpha(hex("#FFC27A"), 0.38f * day), withAlpha(hex("#FFC27A"), 0f)), null)
        c.drawRect(-w, py - 10f, w * 2f, baseY, F); F.shader = null
        c.restore()
        // ---- margem direita: pinheiros, árvores de outono, casas
        var x = w * 0.5f
        val sh = Path(); sh.moveTo(w * 0.5f, shoreY + 2f)
        while (x < w + 10f) {
            val k = (x / w - 0.5f) * 2f
            sh.lineTo(x, shoreY - (4f + r() * (6f + 14f * k)) * dp); x += (2.5f + r() * 4f) * dp
        }
        sh.lineTo(w + 10f, shoreY + 2f); sh.close()
        F.color = tn(hex("#4A5560")); c.drawPath(sh, F)
        val autumn = intArrayOf(hex("#E8683F"), hex("#F08A45"), hex("#D9483C"), hex("#F2A552"), hex("#C9503F"))
        blur(F, 1f * dp)
        for (i in 0 until 60) {
            val bx = w * (0.48f + r() * 0.55f); val rr = (3f + r() * 5f) * dp
            F.color = withAlpha(tn(autumn[rnd.nextInt(5)]), 0.9f)
            c.drawCircle(bx, shoreY - rr * (0.6f + r() * 1.6f), rr, F)
        }
        blur(F, 0f)
        val walls = intArrayOf(hex("#EDE6DE"), hex("#D9CFC6"), hex("#C9D6E2"), hex("#E6D2C0"))
        for (i in 0 until 16) {
            val bx = w * (0.55f + r() * 0.45f); val bw = (5f + r() * 8f) * dp; val bh = (4f + r() * 5f) * dp
            F.color = tn(walls[rnd.nextInt(4)]); c.drawRect(bx, shoreY - bh - 1f * dp, bx + bw, shoreY - 1f * dp, F)
            F.color = tn(if (rnd.nextBoolean()) hex("#6E7C8E") else hex("#8A5F58")); c.drawRect(bx - 1f * dp, shoreY - bh - 2.5f * dp, bx + bw + 1f * dp, shoreY - bh - 0.5f * dp, F)
            if (night > 0.4f) { F.color = withAlpha(hex("#FFD98A"), night); c.drawRect(bx + bw * 0.3f, shoreY - bh * 0.7f, bx + bw * 0.55f, shoreY - bh * 0.35f, F) }
        }
        // ---- colina arborizada à esquerda com luz de contorno
        val hill = Path(); hill.moveTo(-10f, shoreY + 2f); hill.lineTo(-10f, shoreY - h * 0.105f)
        val rim = Path(); var first = true; var cnt = 0
        x = -10f
        val endX = w * 0.56f
        while (x < endX) {
            val u = maxOf(0f, x / endX)
            val yb = shoreY - h * 0.105f * (1f - u.pow(1.7f)) * (1f + 0.12f * sin(u * 5f))
            val yy = yb - (2f + r() * 7f) * dp * (1f - u * 0.6f)
            hill.lineTo(x, yy)
            if (u < 0.7f) { if (first) { rim.moveTo(x, yy); first = false } else rim.lineTo(x, yy) }
            x += (3f + r() * 4f) * dp; cnt++
        }
        hill.lineTo(endX, shoreY + 2f); hill.close()
        lin(F, 0f, shoreY - h * 0.11f, 0f, shoreY, intArrayOf(tn(hex("#6A5A45")), tn(hex("#4A3B3A")), tn(hex("#5A4A52"))), floatArrayOf(0f, 0.6f, 1f))
        c.drawPath(hill, F); F.shader = null
        c.save(); c.clipPath(hill)
        val cols = intArrayOf(hex("#6F7A4E"), hex("#8B6B45"), hex("#B5573F"), hex("#4F5E46"), hex("#C9793F"), hex("#3F4B40"), hex("#A84A3E"))
        blur(F, 1.2f * dp)
        for (i in 0 until 900) {
            F.color = withAlpha(tn(cols[rnd.nextInt(cols.size)]), 0.35f)
            c.drawCircle(r() * endX, shoreY - r() * h * 0.115f, (1.5f + r() * 3.2f) * dp, F)
        }
        blur(F, 0f)
        rad(F, -w * 0.05f, shoreY - h * 0.1f, w * 0.6f, withAlpha(hex("#FFD08A"), 0.55f * day), withAlpha(hex("#FFD08A"), 0f))
        c.drawRect(-10f, shoreY - h * 0.13f, w * 0.6f, shoreY + 2f, F); F.shader = null
        c.restore()
        L.color = withAlpha(hex("#FFE0A8"), 0.55f * day); L.strokeWidth = 1.6f * dp; blur(L, 1.5f * dp)
        c.drawPath(rim, L); blur(L, 0f)
        // ---- ponte em arcos
        val bx0 = w * 0.34f; val bx1 = w * 0.86f; val by = shoreY - 9f * dp
        t.reset(); t.moveTo(bx0, by + 3f * dp); t.quadTo((bx0 + bx1) / 2f, by - 7f * dp, bx1, by + 3f * dp)
        L.color = tn(hex("#DDE6EE")); L.strokeWidth = 3f * dp; c.drawPath(t, L)
        L.color = tn(hex("#AEBFD0")); L.strokeWidth = 1.6f * dp
        fun deckY(u: Float) = by + 3f * dp - 10f * dp * (1f - (2f * u - 1f) * (2f * u - 1f))
        for (i in 0 until 4) {
            val a0 = bx0 + (bx1 - bx0) * i / 4f; val a1 = bx0 + (bx1 - bx0) * (i + 1) / 4f
            val y0 = deckY(i / 4f); val y1 = deckY((i + 1) / 4f)
            t.reset(); t.moveTo(a0, shoreY - 1f * dp); t.quadTo((a0 + a1) / 2f, minOf(y0, y1) + 1f * dp, a1, shoreY - 1f * dp); c.drawPath(t, L)
            if (i > 0) { F.color = tn(hex("#C9D6E2")); c.drawRect(a0 - 2f * dp, y0 + 2f * dp, a0 + 2f * dp, shoreY + 1f * dp, F) }
        }
        // ---- névoa junto à margem
        F.color = withAlpha(lerpColor(tn(hex("#FFF1EC"), 0.6f), sky.hor, 0.3f), 0.6f); blur(F, 16f * dp)
        c.drawOval(-w * 0.3f, shoreY - h * 0.02f, w * 0.75f, shoreY + h * 0.012f, F)
        F.color = withAlpha(tn(0xFFFFFFFF.toInt(), 0.6f), 0.3f); c.drawOval(w * 0.2f, shoreY - h * 0.012f, w * 1.3f, shoreY + h * 0.01f, F)
        blur(F, 0f)
    }

    private fun paintWater(c: Canvas, w: Float, h: Float, dp: Float, shoreY: Float, small: Bitmap, sky: SkyState) {
        val rnd = Random(5)
        fun r() = rnd.nextFloat()
        val night = sky.night
        val F = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val L = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        fun tn(col: Int, k: Float = 0.72f) = lerpColor(col, NIGHTC, night * k)
        val lake = h - shoreY
        // reflexo: parte de cima espelhada e suavizada
        val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG); bp.alpha = 230
        val srcTop = (maxOf(0f, shoreY - lake / 1.12f) * small.width / w).toInt().coerceIn(0, small.height - 1)
        c.save(); c.translate(0f, shoreY * 2f); c.scale(1f, -1f)
        c.drawBitmap(small, Rect(0, srcTop, small.width, small.height), RectF(0f, shoreY - lake, w, shoreY), bp)
        c.restore()
        lin(F, 0f, shoreY, 0f, h, intArrayOf(withAlpha(tn(hex("#7F95B8")), 0.28f), withAlpha(tn(hex("#DDE6F0"), 0.8f), 0.38f), withAlpha(tn(hex("#EEF2F7"), 0.85f), 0.72f)),
            floatArrayOf(0f, 0.45f, 1f))
        c.drawRect(0f, shoreY, w, h, F); F.shader = null
        L.maskFilter = BlurMaskFilter(1f * dp, BlurMaskFilter.Blur.NORMAL)
        for (i in 0 until 170) {
            val y = shoreY + r().pow(1.5f) * lake
            val k = (y - shoreY) / lake
            val x = r() * w; val ln = (20f + r() * 120f) * dp * (0.4f + k)
            L.color = withAlpha(if (r() < 0.6f) 0xFFFFFFFF.toInt() else tn(hex("#5E7396")), 0.05f + 0.08f * r())
            L.strokeWidth = (0.8f + 1.8f * k) * dp
            c.drawLine(x - ln / 2f, y, x + ln / 2f, y, L)
        }
        L.maskFilter = null
        F.color = withAlpha(tn(hex("#33403C")), 0.55f); blur(F, 2f * dp); c.drawRect(0f, shoreY - 1.5f * dp, w, shoreY + 2f * dp, F); blur(F, 0f)
        // barquinho
        val bx = w * 0.5f; val by = shoreY + h * 0.03f
        F.color = tn(hex("#3A3F4A")); c.drawOval(bx - 9f * dp, by - 2f * dp, bx + 9f * dp, by + 2.4f * dp, F)
        c.drawRect(bx - 1f * dp, by - 9f * dp, bx + 1f * dp, by, F)
        F.color = tn(hex("#D9574A"), 0.5f); c.drawCircle(bx, by - 9.5f * dp, 2.2f * dp, F)
        F.color = withAlpha(tn(hex("#3A3F4A")), 0.3f); c.drawOval(bx - 8f * dp, by + 3f * dp, bx + 8f * dp, by + 9f * dp, F)
    }

    private val TIPS = floatArrayOf(-150f, 0.55f, -100f, 0.8f, -50f, 0.95f, 0f, 1f, 50f, 0.95f, 100f, 0.8f, 150f, 0.55f)
    val REDS = intArrayOf(hex("#D9523F"), hex("#E8703F"), hex("#C7402F"), hex("#EF8A4A"), hex("#B93A32"), hex("#E25B3A"))

    /** Folha de bordo (momiji) com 7 pontas. */
    fun mapleLeaf(p: Path, x: Float, y: Float, r: Float, rotDeg: Float) {
        p.reset()
        for (i in 0 until 7) {
            val a = TIPS[i * 2]; val rr = TIPS[i * 2 + 1]
            val an = Math.toRadians((a - 90f + rotDeg).toDouble()).toFloat()
            val px = x + cos(an) * r * rr; val py = y + sin(an) * r * rr
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            if (i < 6) {
                val a2 = Math.toRadians(((a + TIPS[(i + 1) * 2]) / 2f - 90f + rotDeg).toDouble()).toFloat()
                p.lineTo(x + cos(a2) * r * 0.36f, y + sin(a2) * r * 0.36f)
            }
        }
        val an = Math.toRadians((90f + rotDeg).toDouble()).toFloat()
        p.lineTo(x + cos(an) * r * 0.2f, y + sin(an) * r * 0.2f)
        p.close()
    }

    private fun paintForeground(c: Canvas, w: Float, h: Float, dp: Float, night: Float) {
        val rnd = Random(8)
        fun r() = rnd.nextFloat()
        val F = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val L = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        fun tn(col: Int, k: Float = 0.72f) = lerpColor(col, NIGHTC, night * k)
        val y0 = h * 0.885f
        rad(F, w * 0.1f, h * 0.8f, w * 0.75f, withAlpha(hex("#FFD890"), 0.5f * (1f - night)), withAlpha(hex("#FFD890"), 0f))
        c.drawRect(0f, h * 0.5f, w, y0 + 10f, F); F.shader = null
        val soil = Path(); soil.moveTo(-10f, h + 10f); soil.lineTo(-10f, y0 + 12f * dp)
        var x = -10f
        while (x < w + 20f) { soil.lineTo(x, y0 + (8f + r() * 10f) * dp + 10f * dp * sin(x / w * 6f)); x += 22f * dp }
        soil.lineTo(w + 10f, h + 10f); soil.close()
        lin(F, 0f, y0, 0f, h, intArrayOf(tn(hex("#8A5E44")), tn(hex("#6B4636"))), null); c.drawPath(soil, F); F.shader = null
        // pedras
        val bases = intArrayOf(hex("#8E8794"), hex("#7F7A8C"), hex("#9A8F92"), hex("#746F82"))
        val p = Path()
        x = -20f * dp
        while (x < w + 20f * dp) {
            val rw = (28f + r() * 52f) * dp; val rh = rw * (0.38f + r() * 0.22f)
            val cx = x + rw / 2f; val cy = y0 + (10f + r() * 14f) * dp
            x += rw * (0.75f + r() * 0.5f)
            F.color = withAlpha(tn(hex("#3A2C2E")), 0.35f); blur(F, 5f * dp)
            c.drawOval(cx - rw * 0.55f, cy + rh * 0.2f, cx + rw * 0.55f, cy + rh * 0.75f, F); blur(F, 0f)
            p.reset()
            for (i in 0 until 9) {
                val a = (2.0 * PI * i / 9).toFloat()
                val rr = 0.5f * (0.86f + r() * 0.22f)
                val qx = cx + cos(a) * rw * rr; val qy = cy + sin(a) * rh * rr * (if (sin(a) < 0f) 1f else 0.6f)
                if (i == 0) p.moveTo(qx, qy) else p.lineTo(qx, qy)
            }
            p.close()
            val base = bases[rnd.nextInt(4)]
            lin(F, cx - rw * 0.3f, cy - rh * 0.5f, cx + rw * 0.3f, cy + rh * 0.4f,
                intArrayOf(tn(lerpColor(base, hex("#FFE2C4"), 0.45f)), tn(base), tn(lerpColor(base, hex("#3B3048"), 0.5f))), floatArrayOf(0f, 0.45f, 1f))
            F.pathEffect = CornerPathEffect(rw * 0.12f); c.drawPath(p, F); F.pathEffect = null; F.shader = null
        }
        // seixos
        val peb = intArrayOf(hex("#9C93A4"), hex("#B8A6A0"), hex("#C97A6A"), hex("#7E7890"), hex("#D9C2B0"))
        for (i in 0 until 46) {
            val qx = r() * w; val qy = y0 + (26f + r() * 70f) * dp; val rr = (3f + r() * 6f) * dp
            F.color = tn(peb[rnd.nextInt(5)]); c.drawOval(qx - rr, qy - rr * 0.6f, qx + rr, qy + rr * 0.6f, F)
            F.color = withAlpha(0xFFFFFFFF.toInt(), 0.25f * (1f - night)); c.drawOval(qx - rr * 0.5f, qy - rr * 0.45f, qx + rr * 0.2f, qy - rr * 0.1f, F)
        }
        // folhas caídas
        val leaf = Path()
        for (i in 0 until 9) {
            mapleLeaf(leaf, r() * w, y0 + (30f + r() * 60f) * dp, (7f + r() * 5f) * dp, r() * 360f)
            F.color = tn(REDS[rnd.nextInt(3)]); c.drawPath(leaf, F)
        }
        // plantas
        val greens = intArrayOf(hex("#B9983F"), hex("#8F8A3C"), hex("#C9A84A"), hex("#6F7A38"), hex("#A8782F"))
        val sp = floatArrayOf(0.04f, 70f, 0.25f, 0.13f, 54f, -0.1f, 0.24f, 40f, 0.3f, 0.9f, 76f, -0.3f, 0.97f, 60f, 0.1f, 0.8f, 44f, -0.15f, 0.55f, 30f, 0.1f)
        val lf = Path(); val st = Path()
        for (k in 0 until 7) {
            val x0 = w * sp[k * 3]; val hgt = sp[k * 3 + 1] * dp; val lean = sp[k * 3 + 2]
            val yb = y0 + (30f + r() * 20f) * dp
            for (j in 0 until 4) {
                val a = lean + (j - 2f) * 0.28f + (r() - 0.5f) * 0.2f
                val ln = hgt * (0.6f + r() * 0.5f)
                L.color = tn(hex("#6E5A32")); L.strokeWidth = 2f * dp
                st.reset(); st.moveTo(x0, yb); st.quadTo(x0 + sin(a) * ln * 0.4f - lean * 6f * dp, yb - cos(a) * ln * 0.6f, x0 + sin(a) * ln, yb - cos(a) * ln); c.drawPath(st, L)
                for (q in 0 until 5) {
                    val t = 0.35f + q * 0.15f
                    val la = a + (if (q % 2 == 1) 1f else -1f) * 0.9f
                    c.save(); c.translate(x0 + sin(a) * ln * t, yb - cos(a) * ln * t); c.rotate(Math.toDegrees(la.toDouble()).toFloat())
                    F.color = tn(greens[rnd.nextInt(5)])
                    val s = (11f + r() * 7f) * dp
                    lf.reset(); lf.moveTo(0f, 0f); lf.quadTo(s * 0.35f, -s * 0.5f, 0f, -s); lf.quadTo(-s * 0.35f, -s * 0.5f, 0f, 0f); c.drawPath(lf, F)
                    c.restore()
                }
            }
        }
    }
}
