package br.com.coffeelabs.ninho

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.sin

class Particle {
    var alive = false
    var type = 0
    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var life = 0f; var max = 1f
    var size = 10f
    var rot = 0f; var vr = 0f
    var color = 0
}

class Particles(private val dp: Float) {
    companion object {
        const val HEART = 0
        const val SPARKLE = 1
        const val NOTE = 2
        const val ZZZ = 3
        const val CRUMB = 4
        const val SHELL = 5
        const val PUFF = 6
        const val RIPPLE = 7
    }

    private val pool = Array(220) { Particle() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val heart = Path().apply {
        moveTo(0f, 0.35f)
        cubicTo(-0.05f, 0.3f, -0.5f, 0.05f, -0.5f, -0.2f)
        cubicTo(-0.5f, -0.45f, -0.15f, -0.55f, 0f, -0.3f)
        cubicTo(0.15f, -0.55f, 0.5f, -0.45f, 0.5f, -0.2f)
        cubicTo(0.5f, 0.05f, 0.05f, 0.3f, 0f, 0.35f)
        close()
    }
    private val star = Path().apply {
        moveTo(0f, -0.5f); quadTo(0.08f, -0.08f, 0.5f, 0f); quadTo(0.08f, 0.08f, 0f, 0.5f)
        quadTo(-0.08f, 0.08f, -0.5f, 0f); quadTo(-0.08f, -0.08f, 0f, -0.5f); close()
    }
    private val shell = Path().apply {
        moveTo(-0.5f, 0.1f); lineTo(-0.3f, -0.35f); lineTo(-0.05f, -0.15f); lineTo(0.2f, -0.4f)
        lineTo(0.5f, 0f); quadTo(0.1f, 0.45f, -0.5f, 0.1f); close()
    }

    fun spawn(type: Int, x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float, color: Int, vr: Float = 0f) {
        val p = pool.firstOrNull { !it.alive } ?: return
        p.alive = true; p.type = type; p.x = x; p.y = y; p.vx = vx; p.vy = vy
        p.life = 0f; p.max = life; p.size = size; p.color = color; p.rot = 0f; p.vr = vr
    }

    fun clear() { for (p in pool) p.alive = false }

    fun update(dt: Float) {
        for (p in pool) {
            if (!p.alive) continue
            p.life += dt
            if (p.life >= p.max) { p.alive = false; continue }
            when (p.type) {
                CRUMB, SHELL -> p.vy += 900f * dp * dt
                HEART, NOTE -> p.vx += sin(p.life * 5f + p.x) * 30f * dp * dt
                ZZZ -> p.vx = sin(p.life * 2f) * 10f * dp
            }
            p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
            if (p.type == HEART || p.type == NOTE || p.type == PUFF) { p.vx *= 0.98f; p.vy *= 0.985f }
        }
    }

    fun draw(c: Canvas) {
        for (p in pool) {
            if (!p.alive) continue
            val u = p.life / p.max
            val a = smooth(u / 0.15f) * smooth((1f - u) / 0.4f)
            when (p.type) {
                HEART -> {
                    c.save(); c.translate(p.x, p.y); val s = p.size * (0.7f + 0.3f * smooth(u * 5f)); c.scale(s, s)
                    fill.color = withAlpha(p.color, a); c.drawPath(heart, fill); c.restore()
                }
                SPARKLE -> {
                    c.save(); c.translate(p.x, p.y); c.rotate(p.rot); val s = p.size * (1f - u * 0.5f); c.scale(s, s)
                    fill.color = withAlpha(p.color, a); c.drawPath(star, fill); c.restore()
                }
                NOTE -> {
                    fill.color = withAlpha(p.color, a); line.color = fill.color; line.strokeWidth = p.size * 0.14f
                    c.oval(p.x, p.y, p.size * 0.32f, p.size * 0.24f, fill)
                    c.drawLine(p.x + p.size * 0.28f, p.y, p.x + p.size * 0.28f, p.y - p.size * 0.8f, line)
                    c.drawLine(p.x + p.size * 0.28f, p.y - p.size * 0.8f, p.x + p.size * 0.6f, p.y - p.size * 0.6f, line)
                }
                ZZZ -> {
                    text.color = withAlpha(p.color, a); text.textSize = p.size * (0.7f + u * 0.6f)
                    c.drawText("z", p.x, p.y, text)
                }
                CRUMB -> { fill.color = withAlpha(p.color, a); c.drawCircle(p.x, p.y, p.size, fill) }
                SHELL -> {
                    c.save(); c.translate(p.x, p.y); c.rotate(p.rot); c.scale(p.size, p.size)
                    fill.color = withAlpha(p.color, a); c.drawPath(shell, fill); c.restore()
                }
                PUFF -> {
                    fill.color = withAlpha(p.color, a * 0.6f)
                    val s = p.size * (0.6f + u * 0.8f)
                    c.drawCircle(p.x, p.y, s, fill); c.drawCircle(p.x + s * 0.7f, p.y + s * 0.2f, s * 0.7f, fill)
                    c.drawCircle(p.x - s * 0.7f, p.y + s * 0.25f, s * 0.6f, fill)
                }
                RIPPLE -> {
                    line.color = withAlpha(p.color, (1f - u) * 0.7f); line.strokeWidth = 2f * dp
                    val s = p.size * (0.3f + u)
                    c.oval(p.x, p.y, s, s * 0.4f, line)
                }
            }
        }
    }
}
