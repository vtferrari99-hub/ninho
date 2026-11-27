package br.com.coffeelabs.ninho

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Cenário em camadas recortadas da sprite sheet (céu+Fuji, serras/ponte/cidade, água, margem),
 * com as posições de cada camada em função do deslocamento de paralaxe.
 */
object Backdrop {
    const val SHORE = 0.47f
    const val FAR = 0; const val MID = 1; const val FG = 2; const val WATER = 3
    private val FILES = arrayOf("bg/far.png", "bg/mid.png", "bg/fg.png", "bg/water.png")
    private const val FAR_W = 2444f; private const val FAR_H = 358f
    private const val MID_W = 2576f; private const val MID_H = 328f
    private const val FG_W = 2576f; private const val FG_H = 243f
    /** fator de paralaxe de cada camada (a margem, mais próxima, é a que mais se move). */
    const val PAR_FAR = 0.10f; const val PAR_MID = 0.30f; const val PAR_WATER = 0.5f; const val PAR_FG = 1.0f

    fun load(am: AssetManager): Array<Bitmap> {
        val o = BitmapFactory.Options(); o.inScaled = false
        return Array(4) { i -> am.open(FILES[i]).use { BitmapFactory.decodeStream(it, null, o)!! } }
    }

    /** Retângulo (x, y, w, h) em pixels de cada camada, para tela w×h e deslocamento p (px). */
    fun rect(layer: Int, w: Float, h: Float, p: Float, out: FloatArray) {
        val shore = h * SHORE
        when (layer) {
            FAR -> { val s = 0.0008f * h; out[2] = FAR_W * s; out[3] = FAR_H * s; out[0] = w / 2f - 1219f * s + p * PAR_FAR; out[1] = 0.35f * h - 209f * s }
            MID -> { val s = 0.00046f * h; out[2] = MID_W * s; out[3] = MID_H * s; out[0] = w / 2f - 1080f * s + p * PAR_MID; out[1] = shore - out[3] + 3f }
            FG -> { val s = 0.00056f * h; out[2] = FG_W * s; out[3] = FG_H * s; out[0] = w / 2f - out[2] / 2f + p * PAR_FG; out[1] = h - out[3] }
            else -> { out[0] = 0f; out[1] = shore; out[2] = w; out[3] = h - shore }
        }
    }

    fun nightTint(night: Float): Int {
        val r = lerp(1f, 0.40f, night); val g = lerp(1f, 0.45f, night); val b = lerp(1f, 0.70f, night)
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }

    /** Composição pequena e borrada do cenário, para o vidro fosco do HUD. */
    fun frosted(b: Array<Bitmap>, vw: Float, vh: Float, night: Float): Bitmap {
        val k = 1f / 24f
        val w = maxOf(8, (vw * k).toInt()); val h = maxOf(8, (vh * k).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = LightingColorFilter(nightTint(night), 0)
        val r = FloatArray(4)
        c.scale(k, k)
        rect(FAR, vw, vh, 0f, r)
        c.drawBitmap(b[FAR], Rect(0, 0, b[FAR].width, 2), RectF(r[0], 0f, r[0] + r[2], r[1] + 1f), p)
        c.drawBitmap(b[FAR], null, RectF(r[0], r[1], r[0] + r[2], r[1] + r[3]), p)
        rect(WATER, vw, vh, 0f, r)
        c.drawBitmap(b[WATER], null, RectF(r[0], r[1], r[0] + r[2], r[1] + r[3]), p)
        rect(MID, vw, vh, 0f, r)
        c.drawBitmap(b[MID], null, RectF(r[0], r[1], r[0] + r[2], r[1] + r[3]), p)
        rect(FG, vw, vh, 0f, r)
        c.drawBitmap(b[FG], null, RectF(r[0], r[1], r[0] + r[2], r[1] + r[3]), p)
        return out
    }
}
