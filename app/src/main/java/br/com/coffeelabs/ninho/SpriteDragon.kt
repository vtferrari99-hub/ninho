package br.com.coffeelabs.ninho

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Conjunto de poses de cada fase, a partir dos sprites da sheet. */
class PoseSet(
    val idle: String, val move: List<String>, val happy: String, val eat: String, val sleep: String,
    val rest: List<String>, val refName: String, val refFrac: Float, val walker: Boolean, val long: Boolean,
    val sleepScaleRef: String? = null, val sleepFrac: Float = 0f
) {
    companion object {
        fun of(s: Stage): PoseSet = when (s) {
            Stage.EGG, Stage.BABY -> PoseSet("baby_sit", listOf("baby_walk", "baby_crouch"), "baby_walk", "baby_crouch", "baby_crouch_sleep",
                listOf("baby_sit"), "baby_sit", 0.38f, walker = true, long = false)
            Stage.CHILD -> PoseSet("child_stand", listOf("child_stand", "child_hop"), "child_sparkle", "child_stand", "child_stand_sleep",
                listOf("child_stand"), "child_stand", 0.48f, walker = true, long = false)
            Stage.TEEN -> PoseSet("teen_swim", listOf("teen_swim"), "teen_swim", "teen_swim", "adult_coil5_sleep",
                listOf("teen_swim"), "teen_swim", 0.92f, walker = false, long = true, sleepScaleRef = "adult_coil5", sleepFrac = 0.6f)
            Stage.ADULT -> PoseSet("adult_idle", listOf("adult_open"), "adult_roar", "adult_open", "adult_coil5_sleep",
                listOf("adult_coil1", "adult_coil3", "adult_coil4", "adult_idle"), "adult_idle", 0.70f, walker = false, long = false)
            Stage.ELDER -> PoseSet("elder_coil", listOf("elder_swim"), "elder_coil", "elder_coil", "elder_coil_sleep",
                listOf("elder_coil"), "elder_coil", 0.68f, walker = false, long = false)
        }
    }
}

/** Carrega e desenha os sprites com malha deformável (ondulação, respiração, giro e "squash"). */
class SpriteBank(am: AssetManager) {
    private val bmps = HashMap<String, Bitmap>()
    private val info = HashMap<String, SpriteMeta.Info>()

    init {
        val o = BitmapFactory.Options(); o.inScaled = false
        for (i in SpriteMeta.ALL) {
            info[i.name] = i
            am.open("dragon/${i.name}.png").use { s -> bmps[i.name] = BitmapFactory.decodeStream(s, null, o)!! }
        }
    }

    fun info(name: String): SpriteMeta.Info = info[name] ?: info.values.first()

    private val MW = 22
    private val MH = 12
    private val verts = FloatArray((MW + 1) * (MH + 1) * 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var tintKey = -1

    /** Parâmetros de um desenho. */
    class Look {
        var name = ""
        var x = 0f; var y = 0f          // âncora na tela
        var bottom = false              // âncora no pé (true) ou no centro (false)
        var scale = 1f
        var face = 1                    // 1 direita, -1 esquerda
        var turn = 1f                   // -1..1 (para a virada suave)
        var squash = 0f
        var breath = 0f
        var rot = 0f                    // graus
        var wave = 0f                   // amplitude da ondulação (fração da altura)
        var wavePhase = 0f
        var alpha = 1f
        var mirrorY = Float.NaN         // se definido, desenha espelhado em torno dessa linha
        var headRot = 0f                // graus; positivo = cabeça abaixa (para a frente)
        var tailAmp = 0f                // abano da cauda (fração da altura)
        var tailPhase = 0f
        var flipY = 1f                  // -1..1 (pirueta: mostra a barriga)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Deforma um ponto do sprite (px): abano da cauda e giro da cabeça em torno do pescoço. */
    private fun bend(l: Look, i: SpriteMeta.Info, sx0: Float, sy0: Float, out: FloatArray) {
        var x = sx0; var y = sy0
        val hu = i.headX / i.w
        val span = max(hu, 1f - hu).coerceAtLeast(0.2f)
        val d = (abs(x / i.w - hu) / span).coerceIn(0f, 1f)
        if (l.tailAmp != 0f) {
            val tw = smoothstep(0.45f, 1f, d)
            y += l.tailAmp * i.h * tw * sin(l.tailPhase - d * 4f)
            x += l.tailAmp * i.h * 0.4f * tw * cos(l.tailPhase - d * 4f)
        }
        if (l.headRot != 0f) {
            val hr = 0.32f * minOf(i.w, i.h).toFloat()
            val dh = kotlin.math.hypot(x - i.headX, y - i.headY)
            val wgt = smoothstep(hr * 1.6f, hr * 0.7f, dh)
            if (wgt > 0f) {
                val pvx = i.headX + (i.w / 2f - i.headX) * 0.4f
                val pvy = i.headY + (i.h / 2f - i.headY) * 0.4f
                val a = Math.toRadians((l.headRot * i.face * wgt).toDouble()).toFloat()
                val dx = x - pvx; val dy = y - pvy
                x = pvx + dx * cos(a) - dy * sin(a); y = pvy + dx * sin(a) + dy * cos(a)
            }
        }
        out[0] = x; out[1] = y
    }
    private val bendOut = FloatArray(2)

    /** Converte um ponto do sprite (px) para a tela, com as mesmas transformações (sem a onda). */
    fun toScreen(l: Look, px: Float, py: Float, out: FloatArray) {
        val i = info(l.name)
        val flip = (if (l.face == i.face) 1f else -1f) * (if (l.turn < 0f) -1f else 1f)
        val sx = l.scale * (1f + l.squash * 0.6f) * max(0.08f, abs(l.turn)) * flip
        val sy = l.scale * (1f - l.squash) * (1f + l.breath) * (if (abs(l.flipY) < 0.08f) 0.08f * sign(l.flipY + 1e-6f) else l.flipY)
        bend(l, i, px, py, bendOut)
        val lx = (bendOut[0] - i.w / 2f) * sx
        val ly = (bendOut[1] - (if (l.bottom) i.h.toFloat() else i.h / 2f)) * sy
        val a = Math.toRadians(l.rot.toDouble()).toFloat()
        out[0] = l.x + lx * cos(a) - ly * sin(a); out[1] = l.y + lx * sin(a) + ly * cos(a)
    }

    fun headScreen(l: Look, out: FloatArray) { val i = info(l.name); toScreen(l, i.headX, i.headY, out) }

    fun size(l: Look, out: FloatArray) { val i = info(l.name); out[0] = i.w * l.scale; out[1] = i.h * l.scale }

    fun draw(c: Canvas, l: Look, night: Float) {
        val i = info(l.name)
        val bmp = bmps[l.name] ?: return
        val key = (night * 40f).toInt()
        if (key != tintKey) { tintKey = key; paint.colorFilter = LightingColorFilter(Backdrop.nightTint(night * 0.85f), 0) }
        paint.alpha = (clamp01(l.alpha) * 255f).toInt()
        val flip = (if (l.face == i.face) 1f else -1f) * (if (l.turn < 0f) -1f else 1f)
        val tAbs = max(0.08f, abs(l.turn))
        val sx = l.scale * (1f + l.squash * 0.6f) * tAbs * flip
        val fy = if (abs(l.flipY) < 0.08f) 0.08f * sign(l.flipY + 1e-6f) else l.flipY
        val sy = l.scale * (1f - l.squash) * (1f + l.breath) * fy
        val a = Math.toRadians(l.rot.toDouble()).toFloat()
        val ca = cos(a); val sa = sin(a)
        val hu = i.headX / i.w
        val span = max(hu, 1f - hu).coerceAtLeast(0.2f)
        val baseY = if (l.bottom) i.h.toFloat() else i.h / 2f
        var k = 0
        for (r in 0..MH) {
            val v = r / MH.toFloat()
            for (q in 0..MW) {
                val u = q / MW.toFloat()
                val d = (abs(u - hu) / span).coerceIn(0f, 1f)
                val w = l.wave * i.h * d.pow(1.2f) * sin(2f * PI.toFloat() * d * 1.15f - l.wavePhase)
                // pés/base mais firmes que o topo nos que andam
                val anchorK = if (l.bottom) (1f - v * 0.85f) else 1f
                bend(l, i, u * i.w, v * i.h + w * anchorK, bendOut)
                var lx = (bendOut[0] - i.w / 2f) * sx
                var ly = (bendOut[1] - baseY) * sy
                val rx = lx * ca - ly * sa; val ry = lx * sa + ly * ca
                lx = rx; ly = ry
                verts[k++] = l.x + lx
                verts[k++] = if (l.mirrorY.isNaN()) l.y + ly else 2f * l.mirrorY - (l.y + ly)
            }
        }
        c.drawBitmapMesh(bmp, MW, MH, verts, 0, null, 0, paint)
    }
}
