package br.com.coffeelabs.ninho

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

fun smooth(t: Float): Float {
    val x = clamp01(t)
    return x * x * (3f - 2f * x)
}

/** Aproxima [cur] de [target] de forma independente do framerate. */
fun approach(cur: Float, target: Float, rate: Float, dt: Float): Float {
    val k = 1f - kotlin.math.exp(-rate * dt)
    return cur + (target - cur) * k
}

/** "#RRGGBB" → cor opaca (sem depender do Android, para rodar também em testes). */
fun hex(s: String): Int = (0xFF000000L or s.removePrefix("#").toLong(16)).toInt()

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val k = clamp01(t)
    return Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
    )
}

fun withAlpha(c: Int, a: Float): Int = ((clamp01(a) * 255f).toInt() shl 24) or (c and 0x00FFFFFF)

fun Canvas.oval(cx: Float, cy: Float, rx: Float, ry: Float, p: Paint) {
    drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p)
}

/** Formata uma duração em português curto: "2d 4h", "3h 10min", "12 min". */
fun fmtDuration(ms: Long): String {
    if (ms <= 60_000L) return "menos de 1 min"
    val totalMin = ms / 60_000L
    val d = totalMin / (60 * 24)
    val h = (totalMin / 60) % 24
    val m = totalMin % 60
    return when {
        d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
        h > 0 -> if (m > 0) "${h}h ${m}min" else "${h}h"
        else -> "$m min"
    }
}
