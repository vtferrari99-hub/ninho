package br.com.coffeelabs.ninho

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.Matrix
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

enum class Expr { CALM, JOY, BLISS, LOVE, NEUTRAL, SAD, SLEEP, SLEEPY, CHEW, SURPRISED, ANNOYED }

/**
 * Camada 2D por cima do OpenGL: cuida da lógica do jogo, do toque, das partículas e do HUD,
 * e publica o estado do Ryu para o renderizador 3D.
 */
@SuppressLint("ViewConstructor")
class GameView(ctx: Context, private val st: DragonState, private val host: Host, private val scene: Scene) : View(ctx) {

    interface Host {
        fun askNewEgg()
        fun saveState()
        fun onDarkSky(dark: Boolean)
    }

    private enum class Mode { IDLE, GOTO, GO_EAT, EAT, PLAY, GO_SLEEP, SLEEP, FLEE }

    private val dp = resources.displayMetrics.density
    private val fx = Particles(dp)
    private val rnd = Random()
    private val pi = PI.toFloat()
    private val n = Scene.N

    // ---------------------------------------------------------------- layout / câmera
    private var vw = 0f
    private var vh = 0f
    private var insetTop = 0f
    private var insetBottom = 0f
    private var hudTop = 0f
    private var hudBottom = 0f
    private var dockTop = 0f
    private var dockBottom = 0f
    private val vp = FloatArray(16)
    private val vpInv = FloatArray(16)
    private val tmp4 = FloatArray(4)
    private val out4 = FloatArray(4)
    private var pxUnit = 100f           // pixels por unidade de mundo a 1 unidade da câmera
    private var halfW = 1f              // meia largura visível em z = 0

    // fundo
    private var frost: Bitmap? = null
    private var bgKey = -1
    private var bgBusy = false

    // ---------------------------------------------------------------- tempo
    private var running = false
    private var lastNs = 0L
    private var t = 0f
    private var saveTimer = 0f
    private var hour = 12f
    private var night = 0f

    // ---------------------------------------------------------------- Ryu
    private var swimmer = Swimmer(V3(0f, 0.3f, 0.3f))
    private var shapeCur: RyuShape = RyuShape.of(st.stage)
    private var drawnStage: Stage = st.stage
    private val trailSpine = FloatArray(n * 3)
    private val coil = FloatArray(n * 3)
    private val spine = FloatArray(n * 3)
    private var mode = Mode.IDLE
    private var modeTimer = 3f
    private var home = V3(0f, 0.3f, 0.15f)
    private var goal = V3(0f, 0.3f, 0.15f)
    private var driftTimer = 6f
    private var headF = V3(1f, 0f, 0.3f)
    private var attention = 0.25f
    private var sleepAmt = 0f
    private var sleepPokes = 0
    private var exprOverride = Expr.CALM
    private var exprTimer = 0f
    private var blinkTimer = 3f
    private var blinkPhase = 0f
    private var jaw = 0.05f
    private var nod = 0f
    private var nodV = 0f
    private var wiggle = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var lookTX = 0f
    private var lookTY = 0f
    private var zzzTimer = 0f
    private var headSX = 0f
    private var headSY = 0f
    private var headPx = 60f

    // evolução
    private var evolveT = -1f
    private var evolveTarget: Stage = st.stage
    private var evolveFromEgg = false
    private var evolveSwapped = false

    // ovo
    private var eggWobble = 0f
    private var eggWobbleV = 0f

    // comida
    private var foodMeal: Meal? = null
    private var foodPos = V3.ZERO
    private var foodAmt = 1f
    private var eatTimer = 0f

    // pérola
    private var pearlOn = false
    private var pearl = V3.ZERO
    private var pearlV = V3.ZERO
    private var playTimer = 0f
    private var kickCd = 0f

    // toque
    private var touching = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0f
    private var moved = 0f
    private var lastMX = 0f
    private var lastMY = 0f
    private var lastMT = 0L
    private var onDragon = false
    private var onEgg = false
    private var pressedBtn = -1
    private var hugged = false
    private var rubAcc = 0f
    private var lastTapT = -10f
    private val tapTimes = ArrayList<Float>()
    private var petBudget = 10f
    private var tickleToastT = -100f
    private var annoyToastT = -100f

    // ambiente (folhas de bordo de dia, vaga-lumes à noite)
    private val amb = Array(14) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 0.7f) }

    // UI
    private var toastMsg = ""
    private var toastT = 0f
    private var diaryOpen = false
    private val diaryRects = ArrayList<Pair<RectF, Int>>()
    private var darkReported: Boolean? = null

    private val ui = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val uiLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val txtB = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val txtS = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val heartPath = Path().apply {
        moveTo(0f, 0.35f)
        cubicTo(-0.05f, 0.3f, -0.5f, 0.05f, -0.5f, -0.2f)
        cubicTo(-0.5f, -0.45f, -0.15f, -0.55f, 0f, -0.3f)
        cubicTo(0.15f, -0.55f, 0.5f, -0.45f, 0.5f, -0.2f)
        cubicTo(0.5f, 0.05f, 0.05f, 0.3f, 0f, 0.35f)
        close()
    }
    private val tmpPath = Path()
    private val clipPath = Path()
    private val rect = RectF()

    private val cGlass = hex("#1E2127")
    private val cText = hex("#ECEAE6")
    private val cSub = hex("#A9ADB8")
    private val cGold = hex("#E3C48A")
    private val cFood = hex("#EBB08F")
    private val cPlay = hex("#A9D2C0")
    private val cSleep = hex("#B9B0E6")
    private val cDiary = hex("#A9C6E6")
    private val cSeal = hex("#C8553D")

    init {
        isClickable = true
        buildCoil()
        if (st.exists && st.stage != Stage.EGG) {
            if (st.isSleeping(System.currentTimeMillis())) {
                swimmer.setFromSpine(coil, n); mode = Mode.SLEEP; sleepAmt = 1f
            } else prewarm()
        }
    }

    // ================================================================ ciclo
    fun resume() { running = true; lastNs = 0L; postInvalidateOnAnimation() }
    fun pause() { running = false }

    fun setInsets(top: Int, bottom: Int) { insetTop = top.toFloat(); insetBottom = bottom.toFloat(); relayout() }

    fun onNewDragon() {
        drawnStage = st.stage
        shapeCur = RyuShape.of(st.stage)
        evolveT = -1f
        mode = Mode.IDLE
        foodMeal = null; pearlOn = false
        fx.clear()
        toast("Um ovo prateado apareceu sobre a folha.")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        vw = w.toFloat(); vh = h.toFloat()
        relayout()
        bgKey = -1
    }

    private fun relayout() {
        if (vw <= 0f) return
        hudTop = insetTop + 8f * dp
        hudBottom = hudTop + 112f * dp
        dockBottom = vh - insetBottom - 10f * dp
        dockTop = dockBottom - 92f * dp
        Cam.viewProj(vw / vh, vp)
        Matrix.invertM(vpInv, 0, vp, 0)
        val tanH = tan(Math.toRadians(Cam.FOV / 2.0)).toFloat()
        pxUnit = vh / (2f * tanH)
        halfW = tanH * Cam.EYE.z * vw / vh
    }

    private fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    /** Projeta um ponto do mundo para a tela; devolve a escala (px por unidade) naquela profundidade. */
    private fun project(p: V3): Float {
        tmp4[0] = p.x; tmp4[1] = p.y; tmp4[2] = p.z; tmp4[3] = 1f
        Matrix.multiplyMV(out4, 0, vp, 0, tmp4, 0)
        val w = max(0.01f, out4[3])
        projX = (out4[0] / w * 0.5f + 0.5f) * vw
        projY = (1f - (out4[1] / w * 0.5f + 0.5f)) * vh
        return pxUnit / w
    }
    private var projX = 0f
    private var projY = 0f

    /** Ponto do mundo sob o toque, no plano z = zPlane. */
    private fun unproject(sx: Float, sy: Float, zPlane: Float): V3 {
        val nx = sx / vw * 2f - 1f; val ny = 1f - sy / vh * 2f
        tmp4[0] = nx; tmp4[1] = ny; tmp4[2] = -1f; tmp4[3] = 1f
        Matrix.multiplyMV(out4, 0, vpInv, 0, tmp4, 0)
        val a = V3(out4[0] / out4[3], out4[1] / out4[3], out4[2] / out4[3])
        tmp4[2] = 1f
        Matrix.multiplyMV(out4, 0, vpInv, 0, tmp4, 0)
        val b = V3(out4[0] / out4[3], out4[1] / out4[3], out4[2] / out4[3])
        val d = b - a
        val k = if (abs(d.z) < 1e-5f) 0f else (zPlane - a.z) / d.z
        return a + d * k
    }

    private fun clampRegion(p: V3): V3 {
        val mx = max(0.2f, halfW - 0.35f)
        return V3(p.x.coerceIn(-mx, mx), p.y.coerceIn(-0.55f, 1.05f), p.z.coerceIn(-0.5f, 0.9f))
    }

    // ---------------------------------------------------------------- fundo
    private fun ensureBackground() {
        if (vw <= 0f || bgBusy) return
        val key = (hour * 6f).toInt()
        if (key == bgKey) return
        bgBusy = true
        val k = min(1f, 1080f / vw)
        val bw = (vw * k).toInt(); val bh = (vh * k).toInt()
        val hr = hour; val dens = dp * k
        Thread {
            try {
                val bmp = FujiPainter.paint(bw, bh, hr, dens)
                val fr = FujiPainter.frosted(bmp)
                post {
                    synchronized(scene.lock) { scene.bg = bmp; scene.bgVersion++ }
                    frost = fr
                    bgKey = key
                    bgBusy = false
                }
            } catch (e: Throwable) {
                post { bgBusy = false; bgKey = key }
            }
        }.start()
    }

    // ================================================================ atualização
    private fun update(dt: Float) {
        val now = System.currentTimeMillis()
        t += dt
        st.simulateTo(now)
        hour = Clock.hourOf(now)
        night = SkyState.at(hour).night
        ensureBackground()
        val dark = night > 0.5f
        if (dark != darkReported) { darkReported = dark; host.onDarkSky(dark) }
        toastT -= dt
        fx.update(dt)
        if (!st.exists) { publish(); return }

        st.evolvedTo?.let { startEvolve(it); st.evolvedTo = null; host.saveState() }
        if (st.missed.isNotEmpty()) {
            if (st.stage != Stage.EGG && evolveT < 0f) {
                toast(if (st.missed.size > 1) "${st.name} sentiu falta de algumas refeições…"
                else "${st.name} sentiu falta do ${st.missed[0].label.lowercase()}…")
            }
            st.missed.clear()
        }
        updateEvolve(dt)
        petBudget = min(10f, petBudget + dt / 12f)
        if (exprTimer > 0f) exprTimer -= dt
        blinkTimer -= dt
        if (blinkTimer <= 0f) { blinkPhase = 0.16f; blinkTimer = rf(2.4f, 5.8f) }
        if (blinkPhase > 0f) blinkPhase = max(0f, blinkPhase - dt)

        if (drawnStage == Stage.EGG) updateEgg(dt) else updateDragon(dt, now)
        publish()
        saveTimer += dt
        if (saveTimer > 30f) { saveTimer = 0f; host.saveState() }
    }

    private fun updateEgg(dt: Float) {
        eggWobbleV += (-70f * eggWobble - 5f * eggWobbleV) * dt
        eggWobble += eggWobbleV * dt
        val prog = st.progress()
        if (rnd.nextFloat() < dt * (0.12f + prog * 0.9f)) eggWobbleV += rf(-1f, 1f) * (1.2f + prog * 3f)
        val s = project(RyuBuilder.NEST + V3(0f, 0.3f, 0f))
        headSX = projX; headSY = projY; headPx = s * 0.34f
        if (touching && onEgg && moved > 10f * dp && rnd.nextFloat() < dt * 5f) {
            fx.spawn(Particles.SPARKLE, headSX + rf(-1f, 1f) * headPx, headSY + rf(-1f, 1f) * headPx, 0f, -30f * dp, 0.9f, 9f * dp, hex("#D6E6FA"), rf(-90f, 90f))
        }
    }

    private fun prewarm() {
        // deixa o corpo já estendido ao abrir o app
        val L = shapeCur.L
        var k = 0
        while (k < 360) { stepSwim(1f / 30f, idleTarget(k / 30f), 0.75f, L); k++ }
    }

    private fun idleTarget(tt: Float): V3 {
        val k = (shapeCur.L / 3.3f).coerceIn(0.55f, 1f)
        return clampRegion(home + V3(0.55f * k * sin(tt * 0.45f), 0.4f * k * sin(tt * 0.33f + 1f), 0.16f * k * sin(tt * 0.27f + 0.5f)))
    }

    private fun stepSwim(dt: Float, target: V3, speed: Float, L: Float) {
        swimmer.step(dt, target, speed, L, 0.35f + wiggle * 0.8f)
        val minY = RyuBuilder.WATER_Y + shapeCur.R + 0.12f
        if (swimmer.head.y < minY) swimmer.head = V3(swimmer.head.x, minY, swimmer.head.z)
    }

    private fun buildCoil() {
        val L = shapeCur.L
        val c = RyuBuilder.NEST
        val seg = L / (n - 1)
        var ang = 1.25f
        val y = RyuBuilder.WATER_Y + 0.03f + shapeCur.R * 0.95f
        for (k in 0 until n) {
            val s = k / (n - 1f)
            val r = lerp(min(0.52f, L / 3.6f + 0.12f), 0.16f, s)
            coil[k * 3] = c.x + cos(ang) * r
            coil[k * 3 + 1] = y + (if (k < 6) (6 - k) * 0.012f else 0f)
            coil[k * 3 + 2] = c.z + sin(ang) * r
            ang -= seg / max(0.12f, r)
        }
    }

    private fun updateDragon(dt: Float, now: Long) {
        val sleepingNow = st.isSleeping(now)
        val target = RyuShape.of(drawnStage)
        shapeCur = shapeCur.blend(target, 1f - kotlin.math.exp(-0.9f * dt))
        val L = shapeCur.L
        if (sleepingNow) {
            if (mode != Mode.SLEEP && mode != Mode.GO_SLEEP && mode != Mode.EAT) { endPlay(false); mode = Mode.GO_SLEEP }
        } else if (mode == Mode.SLEEP || mode == Mode.GO_SLEEP) {
            if (mode == Mode.SLEEP) { buildCoil(); swimmer.setFromSpine(coil, n); swimmer.vel = V3(0f, 0.25f, 0f) }
            sleepAmt = 0f
            mode = Mode.IDLE; home = V3(0f, 0.3f, 0.15f)
            setExpr(Expr.JOY, 1.6f)
            sleepPokes = 0
            toast("${st.name} acordou! Bom dia.")
        }

        kickCd -= dt
        when (mode) {
            Mode.IDLE -> {
                driftTimer -= dt
                if (driftTimer <= 0f) {
                    driftTimer = rf(7f, 13f)
                    home = clampRegion(V3(rf(-0.3f, 0.3f), rf(0.05f, 0.5f), rf(0.1f, 0.4f)))
                    decideIdle()
                }
                stepSwim(dt, idleTarget(t), 0.75f, L)
            }
            Mode.GOTO -> {
                stepSwim(dt, goal, 1.3f, L)
                if ((swimmer.head - goal).len() < 0.2f) { mode = Mode.IDLE; home = clampRegion(goal - V3(0f, 0.1f, 0f)); driftTimer = rf(5f, 9f) }
            }
            Mode.FLEE -> {
                stepSwim(dt, goal, 1.5f, L)
                modeTimer -= dt
                if (modeTimer <= 0f) mode = Mode.IDLE
            }
            Mode.GO_EAT -> {
                val tg = foodPos + V3(0f, 0.3f, 0f)
                stepSwim(dt, tg, 1.3f, L)
                if ((swimmer.head - tg).len() < 0.22f) { mode = Mode.EAT; eatTimer = 3.4f; setExpr(Expr.SURPRISED, 0.4f) }
            }
            Mode.EAT -> {
                stepSwim(dt, foodPos + V3(sin(t * 3f) * 0.05f, 0.24f + 0.04f * sin(t * 9f), 0f), 0.5f, L)
                eatTimer -= dt
                foodAmt = clamp01(eatTimer / 3f)
                if (rnd.nextFloat() < dt * 8f) {
                    val s = project(foodPos)
                    fx.spawn(Particles.CRUMB, projX + rf(-0.1f, 0.1f) * s, projY - 0.08f * s, rf(-60f, 60f) * dp, rf(-220f, -120f) * dp, 0.7f, rf(1.5f, 3f) * dp, foodColor())
                }
                if (eatTimer <= 0f) {
                    foodMeal = null
                    mode = Mode.IDLE
                    setExpr(Expr.BLISS, 1.8f); hearts(3); nodV += 3f
                }
            }
            Mode.PLAY -> updatePlay(dt, L)
            Mode.GO_SLEEP -> {
                buildCoil()
                val tg = V3(coil[0], coil[1] + 0.25f, coil[2])
                stepSwim(dt, tg, 0.9f, L)
                if ((swimmer.head - tg).len() < 0.25f) { mode = Mode.SLEEP; setExpr(Expr.SLEEPY, 1.2f) }
            }
            Mode.SLEEP -> {
                buildCoil()
                zzzTimer -= dt
                if (zzzTimer <= 0f && sleepAmt > 0.8f) {
                    zzzTimer = 1.7f
                    fx.spawn(Particles.ZZZ, headSX + headPx * 0.5f, headSY - headPx, 10f * dp, -28f * dp, 2.6f, 22f * dp, hex("#E9ECF8"))
                }
            }
        }
        sleepAmt = approach(sleepAmt, if (mode == Mode.SLEEP) 1f else 0f, 1.6f, dt)
        wiggle = max(0f, wiggle - dt * 1.2f)
        nodV += (-90f * nod - 9f * nodV) * dt
        nod = (nod + nodV * dt).coerceIn(-0.5f, 0.5f)

        // coluna: rastro → (se dormindo) espiral sobre a folha
        swimmer.spine(trailSpine, n, L)
        val sa = smooth(sleepAmt)
        val zPush = 0.85f * (L / 3.3f) * (1f - sa)
        val minY = RyuBuilder.WATER_Y + shapeCur.R + 0.06f
        for (k in 0 until n) {
            val s = k / (n - 1f)
            var x = trailSpine[k * 3]; var y = trailSpine[k * 3 + 1]; var z = trailSpine[k * 3 + 2] - zPush * s
            if (y < minY) y = minY
            if (sa > 0.001f) { x = lerp(x, coil[k * 3], sa); y = lerp(y, coil[k * 3 + 1], sa); z = lerp(z, coil[k * 3 + 2], sa) }
            spine[k * 3] = x; spine[k * 3 + 1] = y; spine[k * 3 + 2] = z
        }
        // orientação da cabeça
        val p0 = V3(spine[0], spine[1], spine[2]); val p3 = V3(spine[9], spine[10], spine[11])
        val along = (p0 - p3).norm()
        val v = swimmer.vel
        val dir = if (v.len() > 0.08f && sa < 0.5f) v.norm() else along
        val hv = V3(dir.x, dir.y * 0.35f, max(dir.z, 0.2f)).norm()
        val att = if (touching || mode == Mode.EAT) 0.5f else if (sa > 0.5f) 0.45f else 0.25f
        attention = approach(attention, att, 3f, dt)
        var ft = hv.lerp(V3(0f, 0.05f, 1f), attention).norm()
        if (mode == Mode.EAT) ft = V3(ft.x, ft.y - 0.5f, ft.z).norm()
        if (ft.dot(along) < -0.2f) ft = (ft + along * 0.6f).norm()
        headF = headF.lerp(ft, 1f - kotlin.math.exp(-5f * dt)).norm()
        val hs = shapeCur.head
        val sc = project(p0 + headF * (0.22f * hs))
        headSX = projX; headSY = projY; headPx = sc * 0.5f * hs

        // expressão → pose
        val e = currentExpr()
        val jawT = when (e) {
            Expr.JOY -> 0.55f; Expr.LOVE -> 0.3f; Expr.BLISS -> 0.25f; Expr.SURPRISED -> 0.4f
            Expr.CHEW -> 0.28f + 0.26f * sin(t * 13f); Expr.SLEEPY -> 0.2f + 0.2f * max(0f, sin(t * 1.5f)); else -> 0.05f
        }
        jaw = approach(jaw, jawT, 14f, dt)
        lookX = approach(lookX, lookTX, 7f, dt); lookY = approach(lookY, lookTY, 7f, dt)
        if (!touching && rnd.nextFloat() < dt * 0.25f) { lookTX = rf(-0.6f, 0.6f); lookTY = rf(-0.4f, 0.3f) }

        if (touching && onDragon && moved > 10f * dp && mode != Mode.SLEEP) setExpr(Expr.LOVE, 0.35f)
        if (touching && onDragon && !hugged && moved < 10f * dp && t - downT > 0.6f) { hugged = true; hug() }
    }

    private fun decideIdle() {
        val r = rnd.nextFloat()
        val m = st.mood()
        when {
            st.energy < 18f && r < 0.6f -> setExpr(Expr.SLEEPY, 3f)
            r < 0.3f && m > 62f -> { loop(); setExpr(Expr.JOY, 1.2f); sparkles(4) }
            r < 0.5f && m < 38f -> {
                setExpr(Expr.SAD, 2.5f)
                fx.spawn(Particles.PUFF, headSX, headSY - headPx * 1.2f, 0f, -20f * dp, 2f, 9f * dp, hex("#C9CEDD"))
            }
            else -> {}
        }
    }

    /** "Pulinho" do Ryu: uma arrancada para cima. */
    private fun loop() {
        swimmer.vel = swimmer.vel + V3(rf(-0.3f, 0.3f), 1.5f, 0f)
        wiggle = 0.6f
    }

    private fun setExpr(e: Expr, dur: Float) { exprOverride = e; exprTimer = dur }

    private fun currentExpr(): Expr {
        if (exprTimer > 0f) {
            if (mode == Mode.SLEEP && exprOverride != Expr.ANNOYED && exprOverride != Expr.SLEEPY) return Expr.SLEEP
            return exprOverride
        }
        if (mode == Mode.SLEEP && sleepAmt > 0.6f) return Expr.SLEEP
        if (mode == Mode.EAT) return Expr.CHEW
        if (st.energy < 18f) return Expr.SLEEPY
        val m = st.mood()
        return when { m > 45f -> Expr.CALM; m > 30f -> Expr.NEUTRAL; else -> Expr.SAD }
    }

    private fun publish() {
        val e = if (st.exists && drawnStage != Stage.EGG) currentExpr() else Expr.CALM
        var open = if (blinkPhase > 0f) 1f - sin((1f - blinkPhase / 0.16f) * pi) else 1f
        when (e) { Expr.SLEEPY -> open *= 0.45f; Expr.ANNOYED -> open *= 0.5f; Expr.SAD -> open *= 0.75f; else -> {} }
        val eyeMode = when (e) { Expr.SLEEP -> 2f; Expr.BLISS, Expr.LOVE -> 1f; else -> 0f }
        synchronized(scene.lock) {
            scene.dragon = st.exists && drawnStage != Stage.EGG
            scene.egg = st.exists && drawnStage == Stage.EGG
            if (scene.dragon) {
                System.arraycopy(spine, 0, scene.spine, 0, spine.size)
                scene.shape = shapeCur
                val p = scene.pose
                p.t = t; p.open = open; p.eyeMode = eyeMode; p.jaw = jaw
                p.headF = headF; p.headU = V3.UP; p.paddle = 1f - sleepAmt; p.nod = nod
            }
            if (scene.egg) {
                var wob = eggWobble
                if (evolveT >= 0f && !evolveSwapped) wob += sin(t * 42f) * 0.16f * smooth(evolveT / swapAt())
                scene.eggWobble = wob
                scene.eggCrack = clamp01((st.progress() - 0.72f) / 0.28f)
                scene.eggGlow = max(smooth((st.progress() - 0.6f) / 0.4f) * (0.6f + 0.4f * sin(t * 2.4f)), evolveGlow())
            }
            scene.pearl = pearlOn; scene.pearlPos = pearl
            scene.night = night; scene.time = t
            scene.lookX = lookX; scene.lookY = lookY
        }
    }

    private fun hearts(k: Int) {
        for (i in 0 until k) {
            fx.spawn(Particles.HEART, headSX + rf(-0.6f, 0.6f) * headPx, headSY - headPx * 0.8f, rf(-30f, 30f) * dp, rf(-90f, -60f) * dp,
                rf(1.2f, 1.8f), rf(14f, 22f) * dp, if (rnd.nextBoolean()) hex("#F2B8C6") else hex("#F6CAD3"))
        }
    }

    private fun sparkles(k: Int, cx: Float = headSX, cy: Float = headSY, spread: Float = headPx * 1.4f) {
        for (i in 0 until k) {
            val a = rf(0f, 2f * pi)
            fx.spawn(Particles.SPARKLE, cx + cos(a) * spread * rf(0.3f, 1f), cy + sin(a) * spread * rf(0.3f, 1f),
                cos(a) * 40f * dp, sin(a) * 40f * dp - 20f * dp, rf(0.7f, 1.3f), rf(8f, 15f) * dp,
                if (rnd.nextBoolean()) hex("#FFF4CF") else hex("#CFE2F7"), rf(-120f, 120f))
        }
    }

    // ---------------------------------------------------------------- evolução
    private fun startEvolve(target: Stage) {
        evolveTarget = target
        if (evolveT >= 0f && !evolveSwapped) return
        evolveT = 0f
        evolveSwapped = false
        evolveFromEgg = drawnStage == Stage.EGG
    }

    private fun swapAt() = if (evolveFromEgg) 1.3f else 0.8f

    private fun updateEvolve(dt: Float) {
        if (evolveT < 0f) return
        evolveT += dt
        if (!evolveSwapped && evolveT >= swapAt()) {
            evolveSwapped = true
            val wasEgg = drawnStage == Stage.EGG
            drawnStage = evolveTarget
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (wasEgg) {
                val b = RyuShape.of(Stage.BABY)
                shapeCur = RyuShape(0.5f, b.R * 0.8f, b.head * 0.8f, b.eye, b.antler, b.whisker, b.mane, b.legs)
                swimmer = Swimmer(RyuBuilder.NEST + V3(0f, 0.35f, 0f))
                swimmer.vel = V3(0.2f, 0.6f, 0f)
                home = V3(0f, 0.2f, 0.3f); mode = Mode.IDLE; sleepAmt = 0f
                val s = project(RyuBuilder.NEST + V3(0f, 0.3f, 0f))
                for (i in 0 until 10) {
                    val a = rf(-pi, 0f)
                    fx.spawn(Particles.SHELL, projX + rf(-0.15f, 0.15f) * s, projY, cos(a) * rf(120f, 260f) * dp, sin(a) * rf(250f, 420f) * dp,
                        1.4f, rf(10f, 16f) * dp, hex("#D6DBE3"), rf(-300f, 300f))
                }
                sparkles(10, projX, projY, s * 0.4f)
                setExpr(Expr.SURPRISED, 1.2f)
                toast("${st.name} nasceu! Bem-vindo ao lago.")
            } else {
                sparkles(12)
                setExpr(Expr.JOY, 2f)
                toast(if (drawnStage == Stage.ELDER) "${st.name} virou um ancião sábio." else "${st.name} cresceu: agora é ${drawnStage.label.lowercase()}!")
            }
        }
        if (evolveSwapped && evolveT > swapAt() + 1.3f) evolveT = -1f
    }

    private fun evolveGlow(): Float {
        if (evolveT < 0f) return 0f
        return if (!evolveSwapped) smooth(evolveT / swapAt()) else 1f - smooth((evolveT - swapAt()) / 1.3f)
    }

    // ---------------------------------------------------------------- brincar (pérola)
    private fun startPlay() {
        pearlOn = true
        pearl = clampRegion(swimmer.head + V3(if (swimmer.head.x > 0f) -0.6f else 0.6f, 0.4f, 0.2f))
        pearlV = V3(0f, 0.2f, 0f)
        playTimer = 13f
        mode = Mode.PLAY
        st.energy -= 8f; st.happiness += 12f; st.clampStats()
        setExpr(Expr.SURPRISED, 0.6f)
    }

    private fun endPlay(celebrate: Boolean) {
        if (!pearlOn) return
        pearlOn = false
        if (celebrate) {
            project(pearl); sparkles(6, projX, projY, 30f * dp)
            setExpr(Expr.BLISS, 1.6f); hearts(2)
            toast("Que brincadeira boa!")
        }
        if (mode == Mode.PLAY) mode = Mode.IDLE
    }

    private fun updatePlay(dt: Float, L: Float) {
        playTimer -= dt
        // a pérola flutua, desacelera e quica nas bordas da área
        pearlV = pearlV * (1f - 0.9f * dt) + V3(0f, sin(t * 1.7f) * 0.25f * dt, 0f)
        var p = pearl + pearlV * dt
        val c = clampRegion(p)
        if (c.x != p.x) pearlV = V3(-pearlV.x * 0.8f, pearlV.y, pearlV.z)
        if (c.y != p.y) pearlV = V3(pearlV.x, -pearlV.y * 0.8f, pearlV.z)
        if (c.z != p.z) pearlV = V3(pearlV.x, pearlV.y, -pearlV.z * 0.8f)
        p = c
        pearl = p
        stepSwim(dt, pearl, 1.5f, L)
        val d = pearl - swimmer.head
        if (kickCd <= 0f && d.len() < 0.34f) {
            kickCd = 0.8f
            val away = (if (d.len() > 0.01f) d.norm() else V3(1f, 0f, 0f))
            pearlV = away * rf(1.3f, 2.0f) + V3(rf(-0.4f, 0.4f), rf(0.3f, 0.9f), rf(-0.3f, 0.3f))
            setExpr(Expr.JOY, 0.9f)
            project(pearl); fx.spawn(Particles.SPARKLE, projX, projY, 0f, -40f * dp, 0.6f, 12f * dp, hex("#FFF4CF"), 90f)
            st.happiness = min(100f, st.happiness + 0.6f)
        }
        if (playTimer <= 0f) endPlay(true)
    }

    // ================================================================ toque
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val ex = e.x; val ey = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; downX = ex; downY = ey; downT = t; moved = 0f; hugged = false; rubAcc = 0f
                lastMX = ex; lastMY = ey; lastMT = e.eventTime
                if (diaryOpen) { onDragon = false; onEgg = false; pressedBtn = -1; return true }
                pressedBtn = buttonAt(ex, ey)
                onEgg = pressedBtn < 0 && st.exists && drawnStage == Stage.EGG && hypot(ex - headSX, ey - headSY) < headPx * 1.5f
                onDragon = pressedBtn < 0 && st.exists && drawnStage != Stage.EGG && hitDragon(ex, ey)
                lookAtFinger(ex, ey)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = hypot(ex - lastMX, ey - lastMY)
                val dtm = max(1L, e.eventTime - lastMT) / 1000f
                moved += d
                lastMX = ex; lastMY = ey; lastMT = e.eventTime
                if (diaryOpen) return true
                lookAtFinger(ex, ey)
                if (onEgg && moved > 10f * dp) {
                    rubAcc += d
                    if (rubAcc > 40f * dp) { rubAcc = 0f; eggWobbleV += rf(-1f, 1f) * 1.5f; st.growthMs += 3000.0 }
                }
                if (onDragon && moved > 10f * dp && hitDragon(ex, ey)) rub(ex, ey, d, d / dtm)
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                val dur = t - downT
                if (diaryOpen) { if (moved < 12f * dp) diaryTap(ex, ey); return true }
                if (pressedBtn >= 0) {
                    if (buttonAt(ex, ey) == pressedBtn) onButton(pressedBtn)
                } else if (onEgg && moved < 12f * dp) tapEgg()
                else if (onDragon && moved < 12f * dp && !hugged && dur < 0.45f) tapDragon()
                else if (!onDragon && !onEgg && moved < 12f * dp) tapWorld(ex, ey)
                pressedBtn = -1; onDragon = false; onEgg = false
                lookTX *= 0.3f; lookTY *= 0.3f
            }
            MotionEvent.ACTION_CANCEL -> { touching = false; pressedBtn = -1; onDragon = false; onEgg = false }
        }
        return true
    }

    private fun lookAtFinger(px: Float, py: Float) {
        val dx = px - headSX; val dy = py - headSY
        val d = max(1f, hypot(dx, dy))
        lookTX = dx / d * 0.8f
        lookTY = -dy / d * 0.8f
    }

    private fun hitDragon(px: Float, py: Float): Boolean {
        if (hypot(px - headSX, py - headSY) < headPx * 1.25f + 8f * dp) return true
        var k = 0
        while (k < n) {
            val s = project(V3(spine[k * 3], spine[k * 3 + 1], spine[k * 3 + 2]))
            if (hypot(px - projX, py - projY) < s * shapeCur.R * 1.3f + 10f * dp) return true
            k += 4
        }
        return false
    }

    private fun buttonAt(px: Float, py: Float): Int {
        if (!st.exists) return -1
        if (py < dockTop || py > dockBottom) return -1
        val left = 16f * dp; val right = vw - 16f * dp
        if (px < left || px > right) return -1
        return ((px - left) / ((right - left) / 4f)).toInt().coerceIn(0, 3)
    }

    private fun haptic() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private fun tapEgg() {
        eggWobbleV += if (rnd.nextBoolean()) 2.4f else -2.4f
        st.growthMs += 15_000.0
        sparkles(3, headSX, headSY, headPx)
        haptic()
        if (rnd.nextFloat() < 0.25f) toast(listOf("O ovo se remexeu!", "Dá para sentir um calorzinho…", "Toc, toc… tem alguém aí?")[rnd.nextInt(3)])
    }

    private fun tapDragon() {
        if (mode == Mode.SLEEP) {
            sleepPokes++
            setExpr(Expr.ANNOYED, 1.3f)
            fx.spawn(Particles.PUFF, headSX, headSY - headPx, 0f, -18f * dp, 1.4f, 7f * dp, hex("#C9CEDD"))
            if (sleepPokes >= 3) { st.happiness -= 0.5f; st.clampStats(); toast("Shh… ${st.name} está dormindo."); sleepPokes = 0 }
            return
        }
        tapTimes.add(t)
        tapTimes.removeAll { t - it > 1.6f }
        if (tapTimes.size >= 6) {
            setExpr(Expr.ANNOYED, 1.8f)
            st.happiness -= 1f; st.clampStats()
            if (mode == Mode.IDLE || mode == Mode.GOTO) {
                mode = Mode.FLEE; modeTimer = 2.5f
                goal = clampRegion(V3(if (swimmer.head.x > 0f) -1f else 1f, 0.9f, -0.4f))
            }
            if (t - annoyToastT > 8f) { annoyToastT = t; toast("Ei, calma! Cutucadas demais.") }
            tapTimes.clear()
            return
        }
        if (t - lastTapT < 0.32f) {
            loop(); sparkles(5); setExpr(Expr.JOY, 1f)
        } else {
            hearts(if (rnd.nextBoolean()) 1 else 2)
            nodV += 4f
            setExpr(Expr.BLISS, 1f)
            if (petBudget >= 1f) { st.happiness += 1.5f; petBudget -= 1f; st.clampStats() }
        }
        lastTapT = t
        haptic()
    }

    private fun rub(px: Float, py: Float, d: Float, speed: Float) {
        rubAcc += d
        if (speed > 2200f * dp) {
            wiggle = 1f
            if (mode != Mode.SLEEP) setExpr(Expr.JOY, 0.6f)
            if (t - tickleToastT > 20f) { tickleToastT = t; toast("Hihi! Cócegas!") }
        }
        if (rubAcc > 28f * dp) {
            rubAcc = 0f
            val note = rnd.nextBoolean()
            fx.spawn(if (note) Particles.NOTE else Particles.HEART, px + rf(-10f, 10f) * dp, py - 20f * dp, rf(-30f, 30f) * dp, -70f * dp, 1.2f,
                (if (note) 16f else 13f) * dp, if (note) hex("#D9D0F2") else hex("#F2B8C6"))
            if (petBudget > 0.1f) { st.happiness += 0.35f; petBudget -= 0.1f; st.clampStats() }
        }
    }

    private fun hug() {
        if (mode == Mode.SLEEP) { hearts(1); return }
        setExpr(Expr.LOVE, 2.4f)
        nodV += 3f
        fx.spawn(Particles.HEART, headSX, headSY - headPx * 1.4f, 0f, -50f * dp, 2f, 40f * dp, hex("#F2B8C6"))
        if (petBudget >= 2f) { st.happiness += 4f; petBudget -= 2f; st.clampStats() }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun tapWorld(px: Float, py: Float) {
        if (!st.exists || drawnStage == Stage.EGG) return
        if (py > dockTop || py < hudBottom) return
        fx.spawn(Particles.RIPPLE, px, py, 0f, 0f, 0.8f, 26f * dp, 0xFFFFFFFF.toInt())
        if (mode != Mode.IDLE && mode != Mode.GOTO) return
        goal = clampRegion(unproject(px, py, 0.2f))
        mode = Mode.GOTO
        setExpr(Expr.SURPRISED, 0.5f)
    }

    // ---------------------------------------------------------------- botões
    private fun onButton(i: Int) {
        haptic()
        val now = System.currentTimeMillis()
        val egg = drawnStage == Stage.EGG || st.stage == Stage.EGG
        val sleeping = st.isSleeping(now)
        when (i) {
            0 -> {
                if (egg) { toast("Ovos não comem! Faça carinho para aquecê-lo."); return }
                if (sleeping && mode == Mode.SLEEP) { toast("Shh… está dormindo. Comida só ao acordar."); return }
                if (mode == Mode.EAT || mode == Mode.GO_EAT) return
                val m = st.feedableMeal(now)
                if (m == null) {
                    val h = Clock.hourOf(now)
                    val cur = Clock.currentMeal(h)
                    if (cur != null && st.fedToday(cur, now)) toast("${st.name} já comeu o ${cur.label.lowercase()}. Barriguinha cheia!")
                    else { val nx = Clock.nextMeal(h); toast("Ainda não é hora. ${nx.label}: das ${nx.start}h às ${nx.end}h.") }
                    return
                }
                endPlay(false)
                st.feed(m, now)
                host.saveState()
                foodMeal = m; foodAmt = 1f
                val side = if (swimmer.head.x > 0f) -1f else 1f
                foodPos = V3(side * min(0.55f, halfW - 0.45f), RyuBuilder.WATER_Y + 0.14f, 1.25f)
                mode = Mode.GO_EAT
                toast("${m.label}: ${m.food}!")
            }
            1 -> {
                if (egg) { toast("Primeiro ele precisa nascer!"); return }
                if (sleeping) { toast("Está na hora de dormir. Brincamos amanhã!"); return }
                if (mode == Mode.PLAY) return
                if (mode == Mode.EAT || mode == Mode.GO_EAT) { toast("Deixa terminar de comer primeiro."); return }
                if (st.energy < 15f) { toast("${st.name} está cansado demais. Que tal uma soneca?"); return }
                startPlay()
                toast("${st.name} adora a pérola!")
            }
            2 -> {
                if (egg) { toast("O ovo já está quentinho sobre a folha."); return }
                if (Clock.isNight(Clock.hourOf(now))) { toast("Já é noite. Boa noite, ${st.name}!"); return }
                if (now < st.napUntil) { st.napUntil = 0L; host.saveState(); return }
                if (st.energy > 70f) { toast("${st.name} está sem sono agora."); return }
                st.napUntil = now + 20 * 60_000L
                host.saveState()
                toast("Soneca de 20 minutos. Bons sonhos!")
            }
            3 -> diaryOpen = true
        }
    }

    private fun diaryTap(px: Float, py: Float) {
        val hit = diaryRects.firstOrNull { it.first.contains(px, py) }?.second
        val now = System.currentTimeMillis()
        when (hit) {
            null, 0 -> diaryOpen = false
            1 -> {
                st.timeScale = when (st.timeScale) { 1f -> 60f; 60f -> 600f; 600f -> 3600f; else -> 1f }
                host.saveState()
                toast(if (st.timeScale == 1f) "Crescimento em tempo real." else "Crescimento ${st.timeScale.toInt()}× mais rápido (teste).")
            }
            2 -> {
                st.freeMeals = !st.freeMeals
                host.saveState()
                toast(if (st.freeMeals) "Refeições liberadas a qualquer hora (teste)." else "Refeições só nos horários.")
            }
            3 -> {
                if (st.stage == Stage.ELDER) { toast("${st.name} já está na fase final."); return }
                st.forceAdvance(now)
                diaryOpen = false
            }
            4 -> { diaryOpen = false; host.askNewEgg() }
            else -> {}
        }
        haptic()
    }

    fun toast(msg: String) { toastMsg = msg; toastT = 3.2f }

    // ================================================================ desenho
    override fun onDraw(c: Canvas) {
        if (vw <= 0f) return
        if (running) {
            val ns = System.nanoTime()
            val dt = if (lastNs == 0L) 0.016f else ((ns - lastNs) / 1e9f).coerceIn(0f, 0.05f)
            lastNs = ns
            update(dt)
        }
        drawAmbient(c)
        if (st.exists) {
            if (foodMeal != null) drawFood(c)
            if (pearlOn) {
                val s = project(pearl)
                ui.shader = RadialGradient(projX, projY, s * 0.5f, withAlpha(hex("#FFFFFF"), 0.35f), withAlpha(hex("#FFFFFF"), 0f), Shader.TileMode.CLAMP)
                ui.alpha = 255; c.drawCircle(projX, projY, s * 0.5f, ui); ui.shader = null
            }
        }
        fx.draw(c)
        val g = evolveGlow()
        if (g > 0.01f && st.exists) {
            val r = headPx * 5f + 60f * dp
            ui.shader = RadialGradient(headSX, headSY, r, intArrayOf(withAlpha(0xFFFFFFFF.toInt(), 0.9f * g), withAlpha(hex("#CFE2F7"), 0.5f * g), withAlpha(hex("#CFE2F7"), 0f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            ui.alpha = 255; c.drawCircle(headSX, headSY, r, ui); ui.shader = null
        }
        drawHud(c)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawAmbient(c: Canvas) {
        val day = 1f - night
        for (a in amb) {
            val sp = a[3]
            if (day > 0.05f) {
                val u = (t * 0.035f * sp + a[0]) % 1f
                val x = (a[1] * 1.2f - 0.1f) * vw + sin(t * 0.9f * sp + a[2]) * 34f * dp + u * 50f * dp
                val y = vh * (0.12f + u * 0.7f)
                val al = day * 0.85f * smooth(u * 6f) * smooth((1f - u) * 4f)
                FujiPainter.mapleLeaf(tmpPath, x, y, (7f + 5f * a[1]) * dp, t * 40f * sp + a[2] * 57f)
                ui.color = withAlpha(FujiPainter.REDS[(a[2] * 0.9f).toInt().coerceIn(0, 5)], al)
                c.save(); c.scale(1f, 0.6f + 0.4f * abs(sin(t * 1.3f * sp + a[2])), x, y)
                c.drawPath(tmpPath, ui); c.restore()
            }
            if (night > 0.05f) {
                val x = (a[0] + sin(t * 0.13f * sp + a[2]) * 0.08f) * vw
                val y = vh * (0.45f + a[1] * 0.4f) + cos(t * 0.21f * sp + a[2] * 2f) * 0.03f * vh
                val al = night * (0.4f + 0.6f * max(0f, sin(t * 1.4f * sp + a[2])))
                ui.shader = RadialGradient(x, y, 14f * dp, withAlpha(hex("#F8EDB4"), 0.55f * al), withAlpha(hex("#F8EDB4"), 0f), Shader.TileMode.CLAMP)
                ui.alpha = 255; c.drawCircle(x, y, 14f * dp, ui); ui.shader = null
                ui.color = withAlpha(hex("#FFF7D6"), al); c.drawCircle(x, y, 2.2f * dp, ui)
            }
        }
    }

    private fun foodColor(): Int = when (foodMeal) {
        Meal.BREAKFAST -> hex("#F2B8C6"); Meal.LUNCH -> hex("#E8B48F"); else -> hex("#F4F1E8")
    }

    private fun drawFood(c: Canvas) {
        val m = foodMeal ?: return
        val s = project(foodPos) * 0.42f
        val x = projX; val y = projY
        fun tn(col: Int) = lerpColor(col, hex("#18244C"), night * 0.5f)
        // bandeja de madeira flutuando
        ui.color = withAlpha(hex("#0E1626"), 0.25f); c.oval(x, y + s * 0.2f, s * 0.75f, s * 0.14f, ui)
        ui.color = tn(hex("#7A5A45")); c.oval(x, y + s * 0.1f, s * 0.7f, s * 0.2f, ui)
        ui.color = tn(hex("#9A7558")); c.oval(x, y + s * 0.06f, s * 0.62f, s * 0.16f, ui)
        val a = foodAmt
        if (a <= 0.02f) return
        when (m) {
            Meal.BREAKFAST -> { // dango
                uiLine.color = tn(hex("#C9A97A")); uiLine.strokeWidth = s * 0.05f
                c.drawLine(x - s * 0.5f, y, x + s * 0.5f, y - s * 0.3f, uiLine)
                val cols = intArrayOf(hex("#F2B8C6"), hex("#F4F1E8"), hex("#B9D6A8"))
                val k = kotlin.math.ceil(a * 3f).toInt()
                for (i in 0 until k) {
                    val bx = x - s * 0.28f + i * s * 0.28f; val by = y - s * 0.07f - i * s * 0.085f
                    ui.color = tn(cols[i]); c.drawCircle(bx, by, s * 0.16f, ui)
                    ui.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f); c.drawCircle(bx - s * 0.05f, by - s * 0.06f, s * 0.04f, ui)
                }
            }
            Meal.LUNCH -> { // peixe grelhado
                val k = 0.45f + 0.55f * a
                c.save(); c.translate(x, y - s * 0.08f); c.scale(k, k)
                ui.color = tn(hex("#E8B48F"))
                tmpPath.reset(); tmpPath.moveTo(s * 0.3f, 0f); tmpPath.lineTo(s * 0.52f, -s * 0.15f); tmpPath.lineTo(s * 0.52f, s * 0.15f); tmpPath.close()
                c.drawPath(tmpPath, ui)
                c.oval(0f, 0f, s * 0.38f, s * 0.16f, ui)
                uiLine.color = tn(hex("#B9805F")); uiLine.strokeWidth = s * 0.03f
                for (j in -1..1) c.drawLine(j * s * 0.12f - s * 0.03f, -s * 0.1f, j * s * 0.12f + s * 0.03f, s * 0.1f, uiLine)
                ui.color = tn(hex("#4A4F5C")); c.drawCircle(-s * 0.24f, -s * 0.03f, s * 0.03f, ui)
                c.restore()
            }
            Meal.DINNER -> { // onigiri
                val k = 0.45f + 0.55f * a
                c.save(); c.translate(x, y - s * 0.12f); c.scale(k, k)
                tmpPath.reset(); tmpPath.moveTo(0f, -s * 0.3f); tmpPath.quadTo(s * 0.36f, s * 0.12f, s * 0.26f, s * 0.2f)
                tmpPath.lineTo(-s * 0.26f, s * 0.2f); tmpPath.quadTo(-s * 0.36f, s * 0.12f, 0f, -s * 0.3f); tmpPath.close()
                ui.color = tn(hex("#F6F3EC")); c.drawPath(tmpPath, ui)
                ui.color = tn(hex("#3A4046")); c.drawRoundRect(-s * 0.13f, s * 0.02f, s * 0.13f, s * 0.21f, s * 0.02f, s * 0.02f, ui)
                c.restore()
            }
        }
    }

    // ---------------------------------------------------------------- HUD (vidro fosco cinza-escuro)
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private fun glass(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float, alpha: Float = 0.62f) {
        val f = frost
        if (f != null && !f.isRecycled) {
            clipPath.reset(); rect.set(l, tp, r, b); clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
            c.save(); c.clipPath(clipPath)
            rect.set(0f, 0f, vw, vh); bmpPaint.alpha = 255
            c.drawBitmap(f, null, rect, bmpPaint)
            c.restore()
        }
        ui.color = withAlpha(cGlass, if (f != null) alpha else 0.82f)
        c.drawRoundRect(l, tp, r, b, rad, rad, ui)
        uiLine.color = withAlpha(0xFFFFFFFF.toInt(), 0.14f); uiLine.strokeWidth = 1f * dp
        c.drawRoundRect(l, tp, r, b, rad, rad, uiLine)
    }

    private fun bar(c: Canvas, l: Float, y: Float, w: Float, h: Float, v: Float, col: Int) {
        ui.color = withAlpha(0xFFFFFFFF.toInt(), 0.13f); c.drawRoundRect(l, y, l + w, y + h, h / 2f, h / 2f, ui)
        if (v > 0.01f) { ui.color = col; c.drawRoundRect(l, y, l + max(h, w * clamp01(v)), y + h, h / 2f, h / 2f, ui) }
    }

    private fun drawHud(c: Canvas) {
        val now = System.currentTimeMillis()
        if (st.exists) {
            val l = 16f * dp; val r = vw - 16f * dp
            glass(c, l, hudTop, r, hudBottom, 22f * dp)
            val pad = 18f * dp
            // selo (hanko) com o kanji de dragão
            val sx = l + pad; val sy = hudTop + 15f * dp; val ss = 24f * dp
            ui.color = cSeal; c.drawRoundRect(sx, sy, sx + ss, sy + ss, 5f * dp, 5f * dp, ui)
            txtS.color = hex("#FBEFE6"); txtS.textSize = ss * 0.72f; txtS.textAlign = Paint.Align.CENTER
            c.drawText("龍", sx + ss / 2f, sy + ss * 0.77f, txtS)
            txtS.color = cText; txtS.textSize = sp(19f); txtS.textAlign = Paint.Align.LEFT
            val nameX = sx + ss + 9f * dp
            c.drawText(st.name, nameX, hudTop + 34f * dp, txtS)
            val nameW = txtS.measureText(st.name)
            val chip = "${st.rarity.label} · Prata"
            txt.textSize = sp(11f); txt.textAlign = Paint.Align.LEFT
            val cw = txt.measureText(chip) + 16f * dp
            val cx0 = nameX + nameW + 9f * dp
            uiLine.color = withAlpha(cGold, 0.7f); uiLine.strokeWidth = 1f * dp
            c.drawRoundRect(cx0, hudTop + 18f * dp, cx0 + cw, hudTop + 38f * dp, 10f * dp, 10f * dp, uiLine)
            txt.color = cGold; c.drawText(chip, cx0 + 8f * dp, hudTop + 32f * dp, txt)
            val sleeping = st.isSleeping(now)
            val mood = st.moodLabel(sleeping)
            txtB.textSize = sp(13f); txtB.textAlign = Paint.Align.RIGHT; txtB.color = cText
            c.drawText(mood, r - pad, hudTop + 32f * dp, txtB)
            val mw = txtB.measureText(mood)
            ui.color = when {
                st.stage == Stage.EGG -> hex("#BFD8F2"); sleeping -> cSleep
                st.mood() > 64f -> hex("#F2B8C6"); st.mood() > 40f -> cGold; else -> hex("#9AA0AE")
            }
            c.drawCircle(r - pad - mw - 10f * dp, hudTop + 27.5f * dp, 5f * dp, ui)
            // linha 2
            txt.textSize = sp(12.5f); txt.color = cText; txt.textAlign = Paint.Align.LEFT
            c.drawText(if (st.stage == Stage.EGG) "Ovo" else "${st.stage.label} · ${st.ageDays(now)}d de vida", l + pad, hudTop + 60f * dp, txt)
            txt.textAlign = Paint.Align.RIGHT; txt.color = cSub
            val nxt = st.stage.next
            val rightBase = when {
                st.stage == Stage.EGG -> "choca em ${fmtDuration(st.remainingMs())}"
                nxt == null -> "fase final"
                else -> "${nxt.label} em ${fmtDuration(st.remainingMs())}"
            }
            c.drawText(if (st.timeScale > 1f) "$rightBase  (${st.timeScale.toInt()}×)" else rightBase, r - pad, hudTop + 60f * dp, txt)
            bar(c, l + pad, hudTop + 67f * dp, r - l - pad * 2f, 5f * dp, st.progress(), cGold)
            if (st.stage == Stage.EGG) {
                txt.textAlign = Paint.Align.LEFT; txt.color = cSub; txt.textSize = sp(12.5f)
                c.drawText("Toque ou esfregue o ovo para aquecê-lo.", l + pad, hudTop + 97f * dp, txt)
            } else {
                val colW = (r - l - pad * 2f) / 3f
                val vals = floatArrayOf(st.satiety, st.happiness, st.energy)
                val cols = intArrayOf(cFood, hex("#F2B8C6"), cSleep)
                for (i in 0..2) {
                    val x0 = l + pad + i * colW
                    val iy = hudTop + 93f * dp
                    drawStatIcon(c, i, x0 + 7f * dp, iy, cols[i])
                    bar(c, x0 + 19f * dp, iy - 2.5f * dp, colW - 30f * dp, 5f * dp, vals[i] / 100f, cols[i])
                }
            }
            drawDock(c, now)
        }
        if (toastT > 0f && toastMsg.isNotEmpty()) {
            val a = min(1f, toastT / 0.4f) * min(1f, (3.2f - toastT) / 0.25f)
            txtB.textSize = sp(13.5f); txtB.textAlign = Paint.Align.CENTER
            val tw = min(txtB.measureText(toastMsg), vw - 64f * dp)
            val y = hudBottom + 32f * dp
            ui.color = withAlpha(cGlass, 0.86f * a)
            c.drawRoundRect(vw / 2f - tw / 2f - 16f * dp, y - 22f * dp, vw / 2f + tw / 2f + 16f * dp, y + 12f * dp, 17f * dp, 17f * dp, ui)
            uiLine.color = withAlpha(0xFFFFFFFF.toInt(), 0.14f * a); uiLine.strokeWidth = 1f * dp
            c.drawRoundRect(vw / 2f - tw / 2f - 16f * dp, y - 22f * dp, vw / 2f + tw / 2f + 16f * dp, y + 12f * dp, 17f * dp, 17f * dp, uiLine)
            txtB.color = withAlpha(cText, a)
            c.drawText(ellipsize(toastMsg, txtB, vw - 64f * dp), vw / 2f, y, txtB)
        }
        if (diaryOpen && st.exists) drawDiary(c, now)
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var e = s
        while (e.length > 4 && p.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    private fun drawStatIcon(c: Canvas, i: Int, cx: Float, cy: Float, col: Int) {
        val r = 6.5f * dp
        when (i) {
            0 -> { // tigela
                tmpPath.reset(); tmpPath.moveTo(cx - r, cy - r * 0.2f); tmpPath.lineTo(cx + r, cy - r * 0.2f)
                tmpPath.quadTo(cx + r * 0.9f, cy + r * 0.9f, cx, cy + r * 0.9f); tmpPath.quadTo(cx - r * 0.9f, cy + r * 0.9f, cx - r, cy - r * 0.2f); tmpPath.close()
                ui.color = col; c.drawPath(tmpPath, ui)
            }
            1 -> { c.save(); c.translate(cx, cy); c.scale(r * 2.1f, r * 2.1f); ui.color = col; c.drawPath(heartPath, ui); c.restore() }
            else -> {
                ui.color = col; c.drawCircle(cx, cy, r * 0.9f, ui)
                ui.color = lerpColor(cGlass, 0xFF505560.toInt(), 0.3f); c.drawCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.75f, ui)
            }
        }
    }

    private fun drawDock(c: Canvas, now: Long) {
        val l = 16f * dp; val r = vw - 16f * dp
        glass(c, l, dockTop, r, dockBottom, 28f * dp)
        val cw = (r - l) / 4f
        val h = Clock.hourOf(now)
        val egg = st.stage == Stage.EGG
        val sleeping = st.isSleeping(now)
        val meal = if (egg) null else st.feedableMeal(now)
        val cur = Clock.currentMeal(h)
        val foodLabel = when {
            egg -> "Comer"
            meal != null -> meal.short
            cur != null && st.fedToday(cur, now) -> "Satisfeito"
            else -> { val nx = Clock.nextMeal(h); "${nx.short} ${nx.start}h" }
        }
        val labels = arrayOf(foodLabel, "Pérola", when { Clock.isNight(h) -> "Boa noite"; now < st.napUntil -> "Acordar"; else -> "Soneca" }, "Diário")
        val enabled = booleanArrayOf(meal != null && mode != Mode.SLEEP, !egg && !sleeping && st.energy >= 15f, !egg && !Clock.isNight(h), true)
        val fg = intArrayOf(cFood, cPlay, cSleep, cDiary)
        for (i in 0..3) {
            val cx = l + cw * (i + 0.5f)
            val cy = dockTop + 37f * dp
            var rad = 25f * dp
            if (i == 0 && meal != null) rad *= 1f + 0.05f * sin(t * 3f)
            if (pressedBtn == i && touching) rad *= 0.92f
            val a = if (enabled[i]) 1f else 0.38f
            ui.color = withAlpha(fg[i], 0.14f * a); c.drawCircle(cx, cy, rad, ui)
            uiLine.color = withAlpha(fg[i], 0.85f * a); uiLine.strokeWidth = 1.4f * dp; c.drawCircle(cx, cy, rad, uiLine)
            drawButtonIcon(c, i, cx, cy, withAlpha(fg[i], a))
            txtB.textSize = sp(11.5f); txtB.textAlign = Paint.Align.CENTER; txtB.color = withAlpha(cText, 0.35f + 0.65f * a)
            c.drawText(labels[i], cx, dockTop + 79f * dp, txtB)
        }
    }

    private fun drawButtonIcon(c: Canvas, i: Int, cx: Float, cy: Float, col: Int) {
        val r = 11f * dp
        ui.color = col; uiLine.color = col; uiLine.strokeWidth = 2f * dp
        when (i) {
            0 -> { // tigela com hashi
                tmpPath.reset()
                tmpPath.moveTo(cx - r, cy); tmpPath.lineTo(cx + r, cy)
                tmpPath.quadTo(cx + r * 0.95f, cy + r * 0.9f, cx, cy + r * 0.9f)
                tmpPath.quadTo(cx - r * 0.95f, cy + r * 0.9f, cx - r, cy); tmpPath.close()
                c.drawPath(tmpPath, ui)
                c.drawLine(cx - r * 0.2f, cy - r * 0.2f, cx + r * 0.75f, cy - r * 1.05f, uiLine)
                c.drawLine(cx + r * 0.1f, cy - r * 0.2f, cx + r * 0.95f, cy - r * 0.85f, uiLine)
            }
            1 -> { // pérola
                c.drawCircle(cx, cy, r * 0.8f, uiLine)
                c.drawCircle(cx - r * 0.28f, cy - r * 0.28f, r * 0.2f, ui)
                c.drawLine(cx + r * 0.95f, cy - r * 0.95f, cx + r * 0.95f, cy - r * 0.45f, uiLine)
                c.drawLine(cx + r * 0.7f, cy - r * 0.7f, cx + r * 1.2f, cy - r * 0.7f, uiLine)
            }
            2 -> { // lua crescente
                tmpPath.reset()
                tmpPath.addCircle(cx, cy, r * 0.9f, Path.Direction.CW)
                clipPath.reset(); clipPath.addCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.75f, Path.Direction.CW)
                tmpPath.op(clipPath, Path.Op.DIFFERENCE)
                c.drawPath(tmpPath, ui)
            }
            else -> { // rolo (makimono)
                rect.set(cx - r * 0.7f, cy - r * 0.75f, cx + r * 0.7f, cy + r * 0.75f)
                c.drawRoundRect(rect, r * 0.12f, r * 0.12f, uiLine)
                c.drawLine(cx - r * 0.95f, cy - r * 0.75f, cx + r * 0.95f, cy - r * 0.75f, uiLine)
                c.drawLine(cx - r * 0.95f, cy + r * 0.75f, cx + r * 0.95f, cy + r * 0.75f, uiLine)
                uiLine.strokeWidth = 1.3f * dp
                c.drawLine(cx - r * 0.35f, cy - r * 0.25f, cx + r * 0.35f, cy - r * 0.25f, uiLine)
                c.drawLine(cx - r * 0.35f, cy + r * 0.2f, cx + r * 0.2f, cy + r * 0.2f, uiLine)
            }
        }
    }

    private val kanji = arrayOf("卵", "幼", "子", "若", "成", "老")

    private fun drawDiary(c: Canvas, now: Long) {
        diaryRects.clear()
        ui.color = withAlpha(hex("#0B0E14"), 0.45f); c.drawRect(0f, 0f, vw, vh, ui)
        val l = 20f * dp; val r = vw - 20f * dp
        val top = hudTop + 4f * dp
        val bottom = dockBottom
        glass(c, l, top, r, bottom, 26f * dp, 0.86f)
        diaryRects.add(Pair(RectF(l, top, r, bottom), -1))
        val pad = 22f * dp
        var y = top + 42f * dp
        txtS.textAlign = Paint.Align.LEFT; txtS.color = cText; txtS.textSize = sp(20f)
        c.drawText("Diário de ${st.name}", l + pad, y, txtS)
        val xC = r - pad - 6f * dp; val yC = y - 7f * dp
        uiLine.color = cSub; uiLine.strokeWidth = 2f * dp
        c.drawLine(xC - 7f * dp, yC - 7f * dp, xC + 7f * dp, yC + 7f * dp, uiLine)
        c.drawLine(xC - 7f * dp, yC + 7f * dp, xC + 7f * dp, yC - 7f * dp, uiLine)
        diaryRects.add(0, Pair(RectF(xC - 24f * dp, yC - 24f * dp, xC + 24f * dp, yC + 24f * dp), 0))

        txt.textAlign = Paint.Align.LEFT; txt.textSize = sp(13f); txt.color = cText
        y += 30f * dp
        val sleeping = st.isSleeping(now)
        val mult = st.rarity.mult.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
        c.drawText("Ryu ${st.rarity.label.lowercase()} — cresce ${mult}× mais devagar", l + pad, y, txt); y += 22f * dp
        c.drawText("Escamas prateadas, olhos azuis", l + pad, y, txt); y += 22f * dp
        c.drawText("Humor: ${st.moodLabel(sleeping)} · ${st.ageDays(now)} dia(s) de vida", l + pad, y, txt); y += 22f * dp
        c.drawText("Saciedade ${st.satiety.toInt()}% · Alegria ${st.happiness.toInt()}% · Energia ${st.energy.toInt()}%", l + pad, y, txt)

        y += 40f * dp
        val stages = Stage.entries
        val span = (r - l - pad * 2f - 20f * dp) / (stages.size - 1)
        val x0 = l + pad + 10f * dp
        uiLine.color = withAlpha(0xFFFFFFFF.toInt(), 0.18f); uiLine.strokeWidth = 2f * dp
        c.drawLine(x0, y, x0 + span * (stages.size - 1), y, uiLine)
        for ((i, s) in stages.withIndex()) {
            val cx = x0 + span * i
            val done = s.ordinal <= st.stage.ordinal
            val cur = s == st.stage
            ui.color = if (cur) cSeal else if (done) hex("#5A6070") else hex("#2E323A")
            c.drawCircle(cx, y, 13f * dp, ui)
            txtS.textAlign = Paint.Align.CENTER; txtS.textSize = 15f * dp; txtS.color = if (done) hex("#FBEFE6") else cSub
            c.drawText(kanji[i], cx, y + 5.5f * dp, txtS)
            txt.textAlign = Paint.Align.CENTER; txt.textSize = sp(10f); txt.color = if (done) cText else cSub
            c.drawText(if (s == Stage.TEEN) "Adolesc." else s.label, cx, y + 30f * dp, txt)
            txt.color = cSub; txt.textSize = sp(9f)
            c.drawText(if (s == Stage.ELDER) "sempre" else fmtDuration(st.stageDurationMs(s).toLong()), cx, y + 43f * dp, txt)
        }

        y += 72f * dp
        txtB.textSize = sp(14f); txtB.textAlign = Paint.Align.LEFT; txtB.color = cGold
        c.drawText("Refeições de hoje", l + pad, y, txtB)
        val h = Clock.hourOf(now)
        txt.textSize = sp(13f); txt.textAlign = Paint.Align.LEFT
        for (m in Meal.entries) {
            y += 22f * dp
            txt.color = cText
            c.drawText("${m.label}  ${m.start}h–${m.end}h", l + pad, y, txt)
            val status = when {
                st.stage == Stage.EGG -> "—"
                st.fedToday(m, now) -> "comeu ✓"
                h >= m.end -> "perdeu"
                h >= m.start -> "agora!"
                else -> "mais tarde"
            }
            txt.textAlign = Paint.Align.RIGHT; txt.color = if (status == "agora!") cFood else cSub
            c.drawText(status, r - pad, y, txt); txt.textAlign = Paint.Align.LEFT
        }

        y += 34f * dp
        c.drawText("Como cuidar", l + pad, y, txtB)
        txt.color = cSub; txt.textSize = sp(12.5f)
        for (tip in arrayOf("Toque: carinho · Dois toques: arrancada feliz", "Segurar: abraço · Esfregar: cafuné", "Toque no cenário e ele nada até lá",
            "Muitas cutucadas seguidas e ele foge bravinho")) { y += 20f * dp; c.drawText(tip, l + pad, y, txt) }

        y += 34f * dp
        c.drawText("Modo teste", l + pad, y, txtB)
        y += 12f * dp
        val bw = (r - l - pad * 2f - 10f * dp) / 2f
        val bh = 40f * dp
        val opts = arrayOf("Tempo: ${st.timeScale.toInt()}×" to 1, "Refeição livre: ${if (st.freeMeals) "sim" else "não"}" to 2, "Avançar fase" to 3, "Novo ovo" to 4)
        for ((i, o) in opts.withIndex()) {
            val bx = l + pad + (i % 2) * (bw + 10f * dp)
            val by = y + (i / 2) * (bh + 10f * dp)
            if (by + bh > bottom - 10f * dp) break
            val rr = RectF(bx, by, bx + bw, by + bh)
            ui.color = withAlpha(if (o.second == 4) cSeal else 0xFFFFFFFF.toInt(), if (o.second == 4) 0.35f else 0.1f)
            c.drawRoundRect(rr, bh / 2f, bh / 2f, ui)
            uiLine.color = withAlpha(0xFFFFFFFF.toInt(), 0.16f); uiLine.strokeWidth = 1f * dp; c.drawRoundRect(rr, bh / 2f, bh / 2f, uiLine)
            txtB.textSize = sp(13f); txtB.textAlign = Paint.Align.CENTER; txtB.color = cText
            c.drawText(o.first, rr.centerX(), rr.centerY() + 4.5f * dp, txtB)
            diaryRects.add(0, Pair(rr, o.second))
        }
        txt.textAlign = Paint.Align.CENTER; txt.textSize = sp(10f); txt.color = cSub
        c.drawText("Ninho · versão de teste · by CoffeeLabs", vw / 2f, bottom - 14f * dp, txt)
    }
}
