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
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Camada 2D por cima do OpenGL: lógica do jogo, o dragão em sprites (com malha deformável),
 * toque, partículas e HUD. O OpenGL desenha o cenário com paralaxe e as plantas do lago.
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
    private val bank = SpriteBank(ctx.assets)

    // ---------------------------------------------------------------- layout
    private var vw = 0f
    private var vh = 0f
    private var insetTop = 0f
    private var insetBottom = 0f
    private var hudTop = 0f
    private var hudBottom = 0f
    private var dockTop = 0f
    private var dockBottom = 0f
    private val vp = FloatArray(16)
    private val tmp4 = FloatArray(4)
    private val out4 = FloatArray(4)
    private var padX = 0f
    private var padY = 0f
    private var padR = 100f
    private var flyX0 = 0f; private var flyX1 = 0f; private var flyY0 = 0f; private var flyY1 = 0f

    // fundo (vidro fosco)
    private var frost: Bitmap? = null
    private var bgKey = -1
    private var bgBusy = false
    private var layers: Array<Bitmap>? = null
    private var tiltRaw = 0f
    private var tilt = 0f

    // ---------------------------------------------------------------- tempo
    private var running = false
    private var lastNs = 0L
    private var t = 0f
    private var saveTimer = 0f
    private var hour = 12f
    private var night = 0f

    // ---------------------------------------------------------------- dragão (2D)
    private var drawnStage: Stage = st.stage
    private var set = PoseSet.of(st.stage)
    private var scale = 1f
    private var px = 0f
    private var py = 0f
    private var vx = 0f
    private var vy = 0f
    private var face = 1f
    private var faceTarget = 1f
    private var jumpY = 0f
    private var jumpV = 0f
    private var squash = 0f
    private var squashV = 0f
    private var wavePhase = 0f
    private var waveAmp = 0.03f
    private var wiggle = 0f
    private var mode = Mode.IDLE
    private var modeTimer = 3f
    private var homeX = 0f
    private var homeY = 0f
    private var goalX = 0f
    private var goalY = 0f
    private var driftTimer = 6f
    private var stepTimer = 0f
    private var stepFrame = 0
    private var restTimer = 0f
    private var restPose: String? = null
    private var poseName = "baby_sit"
    private var poseScale = 1f
    private var prevPose: String? = null
    private var prevScale = 1f
    private var prevStage: Stage = st.stage
    private var fade = 1f
    private var exprPose: String? = null
    private var exprTimer = 0f
    private var sleepPokes = 0
    private var zzzTimer = 0f
    private val look = SpriteBank.Look()
    private val tmp2 = FloatArray(2)
    private var headSX = 0f
    private var headSY = 0f
    private var headPx = 40f
    private var placed = false

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
    private var foodX = 0f
    private var foodY = 0f
    private var foodAmt = 1f
    private var eatTimer = 0f

    // pérola
    private var pearlOn = false
    private var pearlX = 0f
    private var pearlY = 0f
    private var pearlVX = 0f
    private var pearlVY = 0f
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
        if (st.exists && st.stage != Stage.EGG && st.isSleeping(System.currentTimeMillis())) mode = Mode.SLEEP
    }

    // ================================================================ ciclo
    fun resume() { running = true; lastNs = 0L; postInvalidateOnAnimation() }
    fun pause() { running = false }

    fun setInsets(top: Int, bottom: Int) { insetTop = top.toFloat(); insetBottom = bottom.toFloat(); relayout() }

    fun onNewDragon() {
        drawnStage = st.stage
        set = PoseSet.of(st.stage)
        evolveT = -1f
        mode = Mode.IDLE
        foodMeal = null; pearlOn = false
        prevPose = null; fade = 1f
        fx.clear()
        toast("Um ovo de dragão apareceu sobre a folha.")
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
        project(RyuBuilder.NEST.x, RyuBuilder.NEST.y + 0.01f, RyuBuilder.NEST.z)
        padX = out4[0]; padY = out4[1]
        project(RyuBuilder.NEST.x + 0.74f, RyuBuilder.NEST.y, RyuBuilder.NEST.z)
        padR = abs(out4[0] - padX)
        flyX0 = vw * 0.1f; flyX1 = vw * 0.9f
        flyY0 = hudBottom + vh * 0.12f; flyY1 = padY - vh * 0.1f
        applyStage(drawnStage)
        if (!placed) { placeAtRest(); placed = true }
    }

    private fun project(x: Float, y: Float, z: Float) {
        tmp4[0] = x; tmp4[1] = y; tmp4[2] = z; tmp4[3] = 1f
        Matrix.multiplyMV(out4, 0, vp, 0, tmp4, 0)
        val w = max(0.01f, out4[3])
        out4[0] = (out4[0] / w * 0.5f + 0.5f) * vw
        out4[1] = (1f - (out4[1] / w * 0.5f + 0.5f)) * vh
    }

    private fun applyStage(s: Stage) {
        set = PoseSet.of(s)
        val ref = bank.info(set.refName)
        scale = set.refFrac * vw / ref.w
    }

    private fun scaleFor(name: String): Float {
        val ss = set.sleepScaleRef
        if (name == set.sleep && ss != null) return set.sleepFrac * vw / bank.info(ss).w
        return scale
    }

    private fun groundY() = padY - padR * 0.04f

    private fun placeAtRest() {
        if (set.walker) { px = padX; py = groundY() } else { px = vw * 0.5f; py = (flyY0 + flyY1) * 0.5f }
        homeX = px; homeY = py; goalX = px; goalY = py
        if (mode == Mode.SLEEP) sleepSpot()
    }

    private fun sleepSpot() {
        if (set.walker) { px = padX; py = groundY() } else { px = padX; py = padY - bank.info(set.sleep).h * scaleFor(set.sleep) * 0.42f }
    }

    private fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    // ---------------------------------------------------------------- fundo
    private fun ensureBackground() {
        if (vw <= 0f || bgBusy) return
        val key = (night * 8f).toInt()
        if (key == bgKey) return
        bgBusy = true
        val nt = night; val w = vw; val h = vh
        Thread {
            try {
                val ls = layers ?: Backdrop.load(context.assets).also { layers = it }
                val fr = Backdrop.frosted(ls, w, h, nt)
                post { frost = fr; bgKey = key; bgBusy = false }
            } catch (e: Throwable) {
                post { bgBusy = false; bgKey = key }
            }
        }.start()
    }

    /** Inclinação lateral do aparelho (aceleração em x, m/s²), vinda do sensor. */
    fun setTilt(ax: Float) { tiltRaw = ax }

    // ================================================================ atualização
    private fun update(dt: Float) {
        val now = System.currentTimeMillis()
        t += dt
        st.simulateTo(now)
        hour = Clock.hourOf(now)
        night = SkyState.at(hour).night
        ensureBackground()
        val tgt = (tiltRaw / 4f).coerceIn(-1f, 1f) * 0.075f - (if (st.exists && drawnStage != Stage.EGG) (px / vw - 0.5f) * 0.03f else 0f)
        tilt = approach(tilt, tgt, 4f, dt)
        val dark = night > 0.5f
        if (dark != darkReported) { darkReported = dark; host.onDarkSky(dark) }
        toastT -= dt
        fx.update(dt)
        publish()
        if (!st.exists) return

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
        if (drawnStage == Stage.EGG) updateEgg(dt) else updateDragon(dt, now)
        saveTimer += dt
        if (saveTimer > 30f) { saveTimer = 0f; host.saveState() }
    }

    private fun publish() {
        synchronized(scene.lock) {
            scene.dragon = false; scene.egg = false; scene.pearl = false
            scene.night = night; scene.time = t; scene.parallax = tilt
        }
    }

    private fun eggName(): String {
        val p = st.progress()
        return if (p < 0.5f) "egg_idle" else if (p < 0.85f) "egg_wobble" else "egg_crack"
    }

    private fun eggScale() = 0.42f * vw / bank.info("egg_idle").w

    private fun updateEgg(dt: Float) {
        eggWobbleV += (-70f * eggWobble - 5f * eggWobbleV) * dt
        eggWobble += eggWobbleV * dt
        val prog = st.progress()
        if (rnd.nextFloat() < dt * (0.12f + prog * 0.9f)) eggWobbleV += rf(-1f, 1f) * (20f + prog * 60f)
        val es = eggScale()
        headSX = padX; headSY = padY - bank.info("egg_idle").h * es * 0.55f; headPx = bank.info("egg_idle").w * es * 0.35f
        if (touching && onEgg && moved > 10f * dp && rnd.nextFloat() < dt * 5f) {
            fx.spawn(Particles.SPARKLE, headSX + rf(-1f, 1f) * headPx, headSY + rf(-1f, 1f) * headPx, 0f, -30f * dp, 0.9f, 9f * dp, hex("#9FDBFF"), rf(-90f, 90f))
        }
    }

    // ---------------------------------------------------------------- movimento
    private fun steer(tx: Float, ty: Float, speed: Float, dt: Float): Boolean {
        val dx = tx - px; val dy = ty - py
        val d = hypot(dx, dy)
        val maxV = speed * vw
        val desX = if (d > 1f) dx / d * min(maxV, d * 2.2f) else 0f
        val desY = if (d > 1f) dy / d * min(maxV, d * 2.2f) else 0f
        val k = min(1f, dt * 3f)
        vx += (desX - vx) * k; vy += (desY - vy) * k
        px += vx * dt; py += vy * dt
        if (abs(vx) > 0.03f * vw) faceTarget = sign(vx)
        return d < 0.03f * vw
    }

    private fun walk(tx: Float, speed: Float, dt: Float): Boolean {
        val dx = tx - px
        if (abs(dx) < 4f * dp) { vx = 0f; return true }
        vx = sign(dx) * min(speed * vw, abs(dx) * 3f)
        px += vx * dt
        faceTarget = sign(dx)
        stepTimer -= dt
        if (stepTimer <= 0f) { stepTimer = 0.2f; stepFrame++; squashV += 1.6f; if (jumpY <= 0f) { jumpV = 0.05f * vh; jumpY = 0.01f } }
        return false
    }

    private fun hop(power: Float) {
        if (set.walker) { if (jumpY <= 0f) { jumpV = 0.11f * vh * power; jumpY = 0.01f; squashV -= 2f } }
        else { vy -= 0.55f * vh * power; wiggle = 0.6f }
    }

    private fun walkRange() = padR * 0.75f

    private fun updateDragon(dt: Float, now: Long) {
        val sleepingNow = st.isSleeping(now)
        if (sleepingNow) {
            if (mode != Mode.SLEEP && mode != Mode.GO_SLEEP && mode != Mode.EAT) { endPlay(false); mode = Mode.GO_SLEEP }
        } else if (mode == Mode.SLEEP || mode == Mode.GO_SLEEP) {
            mode = Mode.IDLE; driftTimer = 2f
            setExpr(set.happy, 1.6f); hop(0.6f)
            sleepPokes = 0
            toast("${st.name} acordou! Bom dia.")
        }
        kickCd -= dt
        var moving = false
        when (mode) {
            Mode.IDLE -> {
                driftTimer -= dt
                if (driftTimer <= 0f) {
                    driftTimer = rf(6f, 12f)
                    if (set.walker) homeX = padX + rf(-1f, 1f) * walkRange()
                    else { homeX = rf(flyX0 + 0.1f * vw, flyX1 - 0.1f * vw); homeY = rf(flyY0, flyY1) }
                    decideIdle()
                }
                if (set.walker) {
                    moving = !walk(homeX, 0.1f, dt)
                } else {
                    val tx = homeX + sin(t * 0.45f) * 0.12f * vw
                    val ty = homeY + sin(t * 0.33f + 1f) * 0.05f * vh
                    steer(tx.coerceIn(flyX0, flyX1), ty.coerceIn(flyY0, flyY1), 0.22f, dt)
                    moving = hypot(vx, vy) > 0.12f * vw
                }
            }
            Mode.GOTO, Mode.FLEE -> {
                val arrived = if (set.walker) walk(goalX, if (mode == Mode.FLEE) 0.3f else 0.18f, dt) else steer(goalX, goalY, if (mode == Mode.FLEE) 0.7f else 0.45f, dt)
                moving = !arrived
                if (mode == Mode.FLEE) { modeTimer -= dt; if (modeTimer <= 0f) { mode = Mode.IDLE; homeX = px; homeY = py } }
                else if (arrived) { mode = Mode.IDLE; homeX = px; homeY = py; driftTimer = rf(5f, 9f) }
            }
            Mode.GO_EAT -> {
                bank.headScreen(look, tmp2)
                val ox = tmp2[0] - px; val oy = tmp2[1] - py
                val arrived = if (set.walker) walk(foodX - ox * 0.9f, 0.18f, dt) else steer(foodX - ox, foodY - oy - 0.02f * vh, 0.4f, dt)
                moving = !arrived
                if (arrived || hypot(tmp2[0] - foodX, tmp2[1] - foodY) < 0.06f * vw) { mode = Mode.EAT; eatTimer = 3.4f; faceTarget = if (foodX >= px) 1f else -1f }
            }
            Mode.EAT -> {
                vx *= 0.9f; vy *= 0.9f
                eatTimer -= dt
                foodAmt = clamp01(eatTimer / 3f)
                if (sin(t * 12f) > 0.85f) squashV += 0.6f
                if (rnd.nextFloat() < dt * 8f) {
                    fx.spawn(Particles.CRUMB, foodX + rf(-12f, 12f) * dp, foodY - 6f * dp, rf(-60f, 60f) * dp, rf(-220f, -120f) * dp, 0.7f, rf(1.5f, 3f) * dp, foodColor())
                }
                if (eatTimer <= 0f) {
                    foodMeal = null
                    mode = Mode.IDLE; driftTimer = 3f
                    setExpr(set.happy, 1.8f); hearts(3); squashV += 3f
                }
            }
            Mode.PLAY -> moving = updatePlay(dt)
            Mode.GO_SLEEP -> {
                val sx = padX
                val sy = if (set.walker) groundY() else padY - bank.info(set.sleep).h * scaleFor(set.sleep) * 0.42f
                val arrived = if (set.walker) walk(sx, 0.12f, dt) else steer(sx, sy, 0.3f, dt)
                moving = !arrived
                if (arrived) { mode = Mode.SLEEP; vx = 0f; vy = 0f }
            }
            Mode.SLEEP -> {
                vx *= 0.9f; vy *= 0.9f
                zzzTimer -= dt
                if (zzzTimer <= 0f) {
                    zzzTimer = 1.8f
                    fx.spawn(Particles.ZZZ, headSX + headPx * 0.4f, headSY - headPx * 0.8f, 10f * dp, -28f * dp, 2.6f, 22f * dp, hex("#E9ECF8"))
                }
            }
        }
        // limites
        if (set.walker) { py = groundY(); px = px.coerceIn(padX - padR * 1.1f, padX + padR * 1.1f) }
        else if (mode != Mode.SLEEP && mode != Mode.GO_SLEEP) { px = px.coerceIn(flyX0, flyX1); py = py.coerceIn(flyY0 - 0.05f * vh, flyY1 + 0.05f * vh) }
        // pulinho dos que andam
        if (jumpY > 0f || jumpV > 0f) {
            jumpY += jumpV * dt; jumpV -= 0.9f * vh * dt
            if (jumpY <= 0f) { jumpY = 0f; if (jumpV < -0.1f * vh) squashV += 2.5f; jumpV = 0f }
        }
        squashV += (-160f * squash - 12f * squashV) * dt
        squash = (squash + squashV * dt).coerceIn(-0.2f, 0.2f)
        face = approach(face, faceTarget, 8f, dt)
        wiggle = max(0f, wiggle - dt * 1.2f)
        val speedN = hypot(vx, vy) / vw
        val waveT = when {
            mode == Mode.SLEEP -> 0.006f
            set.long -> 0.035f + speedN * 0.25f
            set.walker -> 0.004f
            else -> 0.015f + speedN * 0.12f
        } + wiggle * 0.05f
        waveAmp = approach(waveAmp, waveT, 3f, dt)
        wavePhase += dt * (if (mode == Mode.SLEEP) 0.8f else 2.4f + speedN * 8f)

        // pose
        val want: String = when {
            mode == Mode.SLEEP -> set.sleep
            exprTimer > 0f && exprPose != null -> exprPose!!
            mode == Mode.EAT -> set.eat
            set.walker && jumpY > 0.04f * vh && drawnStage == Stage.CHILD -> "child_jump"
            moving -> if (set.walker) set.move[stepFrame % set.move.size] else set.move[0]
            else -> {
                restTimer -= dt
                if (restTimer <= 0f) { restTimer = rf(6f, 12f); restPose = set.rest[rnd.nextInt(set.rest.size)] }
                restPose ?: set.idle
            }
        }
        setPose(want, if (set.walker && moving) 0.05f else 0.3f)
        if (fade < 1f) fade = min(1f, fade + dt / fadeDur)

        fillLook(look, poseName, poseScale, 1f)
        bank.headScreen(look, tmp2)
        headSX = tmp2[0]; headSY = tmp2[1]
        headPx = bank.info(poseName).h * poseScale * 0.16f
        if (touching && onDragon && moved > 10f * dp && mode != Mode.SLEEP) setExpr(set.happy, 0.4f)
        if (touching && onDragon && !hugged && moved < 10f * dp && t - downT > 0.6f) { hugged = true; hug() }
    }

    private var fadeDur = 0.3f

    private fun setPose(name: String, dur: Float) {
        if (name == poseName && set === lastSet) return
        if (!restPoseValid(name)) return
        prevPose = poseName; prevScale = poseScale; prevStage = drawnStage
        poseName = name; poseScale = scaleFor(name)
        lastSet = set
        fadeDur = max(0.01f, dur)
        fade = if (dur <= 0.05f) 1f else 0f
    }
    private var lastSet: PoseSet? = null
    private fun restPoseValid(name: String) = SpriteMeta.ALL.any { it.name == name }

    private fun fillLook(l: SpriteBank.Look, name: String, sc: Float, alpha: Float) {
        l.name = name
        l.scale = sc
        l.bottom = set.walker || drawnStage == Stage.EGG
        l.face = 1
        l.turn = if (abs(face) < 0.08f) (if (face < 0f) -0.08f else 0.08f) else face
        l.squash = squash
        l.breath = 0.012f * sin(t * 2f * pi / (if (mode == Mode.SLEEP) 4.2f else 3f))
        val bob = if (set.walker) -jumpY else sin(t * 1.6f) * 0.01f * vh
        l.x = px + (if (wiggle > 0.01f) sin(t * 40f) * 3f * dp * wiggle else 0f)
        l.y = py + bob
        l.rot = if (set.walker) 0f else (vy / vh * 25f).coerceIn(-12f, 12f) * sign(face) + (if (wiggle > 0.01f) sin(t * 30f) * 4f * wiggle else 0f)
        l.wave = waveAmp
        l.wavePhase = wavePhase
        l.alpha = alpha
        l.mirrorY = Float.NaN
    }

    private fun decideIdle() {
        val r = rnd.nextFloat()
        val m = st.mood()
        when {
            st.energy < 18f && r < 0.6f -> { fx.spawn(Particles.PUFF, headSX, headSY - headPx, 0f, -20f * dp, 2f, 9f * dp, hex("#C9CEDD")) }
            r < 0.3f && m > 62f -> { hop(0.8f); setExpr(set.happy, 1.2f); sparkles(4) }
            r < 0.5f && m < 38f -> fx.spawn(Particles.PUFF, headSX, headSY - headPx * 1.2f, 0f, -20f * dp, 2f, 9f * dp, hex("#C9CEDD"))
            else -> {}
        }
    }

    private fun setExpr(pose: String, dur: Float) { exprPose = pose; exprTimer = dur }

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
                if (rnd.nextBoolean()) hex("#FFF4CF") else hex("#9FDBFF"), rf(-120f, 120f))
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
            val wasWalker = set.walker
            drawnStage = evolveTarget
            applyStage(drawnStage)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (wasEgg) {
                mode = Mode.IDLE; px = padX; py = groundY(); homeX = px
                poseName = set.idle; poseScale = scale; prevPose = null; fade = 1f
                for (i in 0 until 10) {
                    val a = rf(-pi, 0f)
                    fx.spawn(Particles.SHELL, padX + rf(-30f, 30f) * dp, padY - 60f * dp, cos(a) * rf(120f, 260f) * dp, sin(a) * rf(250f, 420f) * dp,
                        1.4f, rf(10f, 16f) * dp, hex("#8E939E"), rf(-300f, 300f))
                }
                sparkles(10, padX, padY - 60f * dp, 80f * dp)
                hop(0.8f)
                toast("${st.name} nasceu! Bem-vindo ao lago.")
            } else {
                if (wasWalker && !set.walker) { vy = -0.4f * vh; homeX = vw * 0.5f; homeY = (flyY0 + flyY1) * 0.5f }
                if (!wasWalker && set.walker) py = groundY()
                if (mode == Mode.SLEEP) sleepSpot()
                restPose = null
                setPose(set.happy, 0.6f)
                setExpr(set.happy, 2f)
                sparkles(12)
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
    private fun pearlR() = 0.035f * vw

    private fun startPlay() {
        pearlOn = true
        if (set.walker) { pearlX = (px + (if (px > padX) -1f else 1f) * padR * 0.6f); pearlY = groundY() - 0.2f * vh }
        else { pearlX = if (px > vw / 2f) vw * 0.25f else vw * 0.75f; pearlY = (flyY0 + flyY1) / 2f }
        pearlVX = 0f; pearlVY = 0f
        playTimer = 13f
        mode = Mode.PLAY
        st.energy -= 8f; st.happiness += 12f; st.clampStats()
    }

    private fun endPlay(celebrate: Boolean) {
        if (!pearlOn) return
        pearlOn = false
        if (celebrate) { sparkles(6, pearlX, pearlY, 30f * dp); setExpr(set.happy, 1.6f); hearts(2); toast("Que brincadeira boa!") }
        if (mode == Mode.PLAY) { mode = Mode.IDLE; homeX = px; homeY = py }
    }

    private fun updatePlay(dt: Float): Boolean {
        playTimer -= dt
        val r = pearlR()
        if (set.walker) {
            val gy = groundY() - r
            pearlVY += 1.6f * vh * dt
            pearlX += pearlVX * dt; pearlY += pearlVY * dt
            pearlVX *= (1f - 1.2f * dt)
            if (pearlY > gy) { pearlY = gy; pearlVY = -pearlVY * 0.5f; if (abs(pearlVY) < 0.03f * vh) pearlVY = 0f }
            val lo = padX - padR * 1.05f; val hi = padX + padR * 1.05f
            if (pearlX < lo) { pearlX = lo; pearlVX = abs(pearlVX) * 0.8f }
            if (pearlX > hi) { pearlX = hi; pearlVX = -abs(pearlVX) * 0.8f }
        } else {
            pearlVX *= (1f - 0.9f * dt); pearlVY = pearlVY * (1f - 0.9f * dt) + sin(t * 1.7f) * 0.02f * vh * dt
            pearlX += pearlVX * dt; pearlY += pearlVY * dt
            if (pearlX < flyX0) { pearlX = flyX0; pearlVX = abs(pearlVX) * 0.8f }
            if (pearlX > flyX1) { pearlX = flyX1; pearlVX = -abs(pearlVX) * 0.8f }
            if (pearlY < flyY0) { pearlY = flyY0; pearlVY = abs(pearlVY) * 0.8f }
            if (pearlY > flyY1) { pearlY = flyY1; pearlVY = -abs(pearlVY) * 0.8f }
        }
        val ox = headSX - px; val oy = headSY - py
        val moving = if (set.walker) !walk(pearlX - ox * 0.8f, 0.22f, dt) else !steer(pearlX - ox, pearlY - oy, 0.6f, dt)
        if (kickCd <= 0f && hypot(pearlX - headSX, pearlY - headSY) < r + headPx * 1.4f) {
            kickCd = 0.8f
            val dir = if (pearlX >= headSX) 1f else -1f
            if (set.walker) { pearlVX = dir * rf(0.35f, 0.6f) * vw; pearlVY = -rf(0.5f, 0.9f) * vh; hop(0.5f) }
            else { pearlVX = dir * rf(0.4f, 0.7f) * vw; pearlVY = rf(-0.3f, 0.3f) * vh }
            setExpr(set.happy, 0.9f)
            fx.spawn(Particles.SPARKLE, pearlX, pearlY, 0f, -40f * dp, 0.6f, 12f * dp, hex("#9FDBFF"), 90f)
            st.happiness = min(100f, st.happiness + 0.6f)
        }
        if (playTimer <= 0f) endPlay(true)
        return moving
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
                onEgg = pressedBtn < 0 && st.exists && drawnStage == Stage.EGG && hypot(ex - headSX, ey - headSY) < headPx * 1.6f
                onDragon = pressedBtn < 0 && st.exists && drawnStage != Stage.EGG && hitDragon(ex, ey)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = hypot(ex - lastMX, ey - lastMY)
                val dtm = max(1L, e.eventTime - lastMT) / 1000f
                moved += d
                lastMX = ex; lastMY = ey; lastMT = e.eventTime
                if (diaryOpen) return true
                if (onDragon && mode != Mode.SLEEP && abs(ex - px) > 30f * dp) faceTarget = sign(ex - px)
                if (onEgg && moved > 10f * dp) {
                    rubAcc += d
                    if (rubAcc > 40f * dp) { rubAcc = 0f; eggWobbleV += rf(-1f, 1f) * 30f; st.growthMs += 3000.0 }
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
            }
            MotionEvent.ACTION_CANCEL -> { touching = false; pressedBtn = -1; onDragon = false; onEgg = false }
        }
        return true
    }

    private fun hitDragon(x: Float, y: Float): Boolean {
        if (hypot(x - headSX, y - headSY) < headPx * 2.2f + 12f * dp) return true
        bank.size(look, tmp2)
        val w = tmp2[0] * 0.42f; val h = tmp2[1] * 0.42f
        val cy = if (look.bottom) look.y - tmp2[1] / 2f else look.y
        val a = (x - look.x) / max(1f, w); val b = (y - cy) / max(1f, h)
        return a * a + b * b < 1f
    }

    private fun buttonAt(x: Float, y: Float): Int {
        if (!st.exists) return -1
        if (y < dockTop || y > dockBottom) return -1
        val left = 16f * dp; val right = vw - 16f * dp
        if (x < left || x > right) return -1
        return ((x - left) / ((right - left) / 4f)).toInt().coerceIn(0, 3)
    }

    private fun haptic() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private fun tapEgg() {
        eggWobbleV += if (rnd.nextBoolean()) 60f else -60f
        st.growthMs += 15_000.0
        sparkles(3, headSX, headSY, headPx)
        haptic()
        if (rnd.nextFloat() < 0.25f) toast(listOf("O ovo se remexeu!", "Dá para sentir um calorzinho…", "Toc, toc… tem alguém aí?")[rnd.nextInt(3)])
    }

    private fun tapDragon() {
        if (mode == Mode.SLEEP) {
            sleepPokes++
            squashV += 2f
            fx.spawn(Particles.PUFF, headSX, headSY - headPx, 0f, -18f * dp, 1.4f, 7f * dp, hex("#C9CEDD"))
            if (sleepPokes >= 3) { st.happiness -= 0.5f; st.clampStats(); toast("Shh… ${st.name} está dormindo."); sleepPokes = 0 }
            return
        }
        tapTimes.add(t)
        tapTimes.removeAll { t - it > 1.6f }
        if (tapTimes.size >= 6) {
            st.happiness -= 1f; st.clampStats()
            mode = Mode.FLEE; modeTimer = 2.5f
            if (set.walker) goalX = padX + (if (px > padX) -1f else 1f) * walkRange()
            else { goalX = if (px > vw / 2f) flyX0 else flyX1; goalY = flyY0 }
            fx.spawn(Particles.PUFF, headSX, headSY - headPx, 0f, -18f * dp, 1.4f, 8f * dp, hex("#C9CEDD"))
            if (t - annoyToastT > 8f) { annoyToastT = t; toast("Ei, calma! Cutucadas demais.") }
            tapTimes.clear()
            return
        }
        if (t - lastTapT < 0.32f) {
            hop(1f); sparkles(5); setExpr(set.happy, 1f)
        } else {
            hearts(if (rnd.nextBoolean()) 1 else 2)
            squashV += 3f
            if (petBudget >= 1f) { st.happiness += 1.5f; petBudget -= 1f; st.clampStats() }
        }
        lastTapT = t
        haptic()
    }

    private fun rub(x: Float, y: Float, d: Float, speed: Float) {
        rubAcc += d
        if (speed > 2200f * dp) {
            wiggle = 1f
            if (t - tickleToastT > 20f) { tickleToastT = t; toast("Hihi! Cócegas!") }
        }
        if (rubAcc > 28f * dp) {
            rubAcc = 0f
            val note = rnd.nextBoolean()
            fx.spawn(if (note) Particles.NOTE else Particles.HEART, x + rf(-10f, 10f) * dp, y - 20f * dp, rf(-30f, 30f) * dp, -70f * dp, 1.2f,
                (if (note) 16f else 13f) * dp, if (note) hex("#D9D0F2") else hex("#F2B8C6"))
            if (petBudget > 0.1f) { st.happiness += 0.35f; petBudget -= 0.1f; st.clampStats() }
        }
    }

    private fun hug() {
        if (mode == Mode.SLEEP) { hearts(1); return }
        setExpr(set.happy, 2.4f)
        squashV += 3f
        fx.spawn(Particles.HEART, headSX, headSY - headPx * 1.4f, 0f, -50f * dp, 2f, 40f * dp, hex("#F2B8C6"))
        if (petBudget >= 2f) { st.happiness += 4f; petBudget -= 2f; st.clampStats() }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun tapWorld(x: Float, y: Float) {
        if (!st.exists || drawnStage == Stage.EGG) return
        if (y > dockTop || y < hudBottom) return
        fx.spawn(Particles.RIPPLE, x, y, 0f, 0f, 0.8f, 26f * dp, 0xFFFFFFFF.toInt())
        if (mode != Mode.IDLE && mode != Mode.GOTO) return
        if (set.walker) goalX = x.coerceIn(padX - padR * 1.05f, padX + padR * 1.05f)
        else { goalX = x.coerceIn(flyX0, flyX1); goalY = y.coerceIn(flyY0, flyY1) }
        mode = Mode.GOTO
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
                val side = if (px > padX) -1f else 1f
                foodX = padX + side * padR * 0.62f
                foodY = groundY() - 4f * dp
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
                if (egg) { toast("O ovo já está quentinho no ninho."); return }
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

    private fun diaryTap(x: Float, y: Float) {
        val hit = diaryRects.firstOrNull { it.first.contains(x, y) }?.second
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
            if (drawnStage == Stage.EGG) drawEgg(c) else drawDragon(c)
            if (foodMeal != null) drawFood(c)
            if (pearlOn) drawPearl(c)
        }
        fx.draw(c)
        val g = evolveGlow()
        if (g > 0.01f && st.exists) {
            val r = headPx * 6f + 80f * dp
            ui.shader = RadialGradient(headSX, headSY, r, intArrayOf(withAlpha(0xFFFFFFFF.toInt(), 0.9f * g), withAlpha(hex("#9FDBFF"), 0.5f * g), withAlpha(hex("#9FDBFF"), 0f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            ui.alpha = 255; c.drawCircle(headSX, headSY, r, ui); ui.shader = null
        }
        drawHud(c)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawEgg(c: Canvas) {
        val es = eggScale()
        val name = eggName()
        val prog = st.progress()
        val glow = max(smooth((prog - 0.5f) / 0.5f) * (0.5f + 0.5f * sin(t * 2.4f)), evolveGlow())
        if (glow > 0.02f) {
            val r = bank.info(name).w * es * 0.75f
            ui.shader = RadialGradient(padX, padY - r * 0.6f, r, withAlpha(hex("#7FD3FF"), 0.45f * glow), withAlpha(hex("#7FD3FF"), 0f), Shader.TileMode.CLAMP)
            ui.alpha = 255; c.drawCircle(padX, padY - r * 0.6f, r, ui); ui.shader = null
        }
        val l = look
        l.name = name; l.scale = es; l.bottom = true; l.face = 1; l.turn = 1f; l.squash = 0f; l.breath = 0f
        var wob = eggWobble
        if (evolveT >= 0f && !evolveSwapped) wob += sin(t * 42f) * 9f * smooth(evolveT / swapAt())
        l.rot = wob; l.wave = 0f; l.wavePhase = 0f; l.alpha = 1f; l.mirrorY = Float.NaN
        l.x = padX; l.y = padY + bank.info(name).h * es * 0.06f
        bank.draw(c, l, night)
    }

    private fun drawDragon(c: Canvas) {
        val s = poseScale
        val inf = bank.info(poseName)
        // sombra na água / na folha
        val airborne = if (set.walker) jumpY else max(0f, padY - (py + inf.h * s * 0.45f))
        val k = 1f - (airborne / (vh * 0.5f)).coerceIn(0f, 0.75f)
        val sw = inf.w * s * 0.42f * k
        val shY = if (set.walker) groundY() + 2f * dp else padY + padR * 0.05f
        c.save(); c.translate(px, shY); c.scale(1f, 0.18f)
        ui.shader = RadialGradient(0f, 0f, max(1f, sw), withAlpha(hex("#0B1426"), 0.38f * k), withAlpha(hex("#0B1426"), 0f), Shader.TileMode.CLAMP)
        ui.alpha = 255; c.drawCircle(0f, 0f, max(1f, sw), ui); ui.shader = null
        c.restore()
        // reflexo (só quando voa, só na faixa do lago)
        if (!set.walker && mode != Mode.SLEEP) {
            c.save(); c.clipRect(0f, padY + padR * 0.12f, vw, dockTop)
            fillLook(look, poseName, s, 0.22f)
            look.mirrorY = padY + padR * 0.1f
            bank.draw(c, look, night)
            c.restore()
        }
        val pp = prevPose
        if (pp != null && fade < 1f) {
            fillLook(look, pp, prevScale, 1f - fade)
            if (prevStage != drawnStage) look.bottom = PoseSet.of(prevStage).walker
            bank.draw(c, look, night)
        }
        fillLook(look, poseName, s, if (pp != null && fade < 1f) fade else 1f)
        bank.draw(c, look, night)
    }

    private fun drawPearl(c: Canvas) {
        val r = pearlR()
        ui.shader = RadialGradient(pearlX, pearlY, r * 3f, withAlpha(hex("#7FD3FF"), 0.55f), withAlpha(hex("#7FD3FF"), 0f), Shader.TileMode.CLAMP)
        ui.alpha = 255; c.drawCircle(pearlX, pearlY, r * 3f, ui)
        ui.shader = RadialGradient(pearlX - r * 0.3f, pearlY - r * 0.3f, r * 1.2f, intArrayOf(0xFFFFFFFF.toInt(), hex("#A9E6FF"), hex("#3C8FD6")),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(pearlX, pearlY, r, ui); ui.shader = null
        ui.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f + 0.4f * sin(t * 5f))
        c.drawCircle(pearlX - r * 0.35f, pearlY - r * 0.35f, r * 0.22f, ui)
    }

    private fun foodColor(): Int = when (foodMeal) {
        Meal.BREAKFAST -> hex("#F2B8C6"); Meal.LUNCH -> hex("#E8B48F"); else -> hex("#F4F1E8")
    }

    private fun drawFood(c: Canvas) {
        val m = foodMeal ?: return
        val s = 0.13f * vw
        val x = foodX; val y = foodY
        fun tn(col: Int) = lerpColor(col, hex("#18244C"), night * 0.5f)
        ui.color = withAlpha(hex("#0E1626"), 0.25f); c.oval(x, y + s * 0.1f, s * 0.75f, s * 0.14f, ui)
        ui.color = tn(hex("#7A5A45")); c.oval(x, y, s * 0.7f, s * 0.2f, ui)
        ui.color = tn(hex("#9A7558")); c.oval(x, y - s * 0.04f, s * 0.62f, s * 0.16f, ui)
        val a = foodAmt
        if (a <= 0.02f) return
        when (m) {
            Meal.BREAKFAST -> {
                uiLine.color = tn(hex("#C9A97A")); uiLine.strokeWidth = s * 0.05f
                c.drawLine(x - s * 0.5f, y - s * 0.1f, x + s * 0.5f, y - s * 0.4f, uiLine)
                val cols = intArrayOf(hex("#F2B8C6"), hex("#F4F1E8"), hex("#B9D6A8"))
                val k = kotlin.math.ceil(a * 3f).toInt()
                for (i in 0 until k) {
                    val bx = x - s * 0.28f + i * s * 0.28f; val by = y - s * 0.17f - i * s * 0.085f
                    ui.color = tn(cols[i]); c.drawCircle(bx, by, s * 0.16f, ui)
                    ui.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f); c.drawCircle(bx - s * 0.05f, by - s * 0.06f, s * 0.04f, ui)
                }
            }
            Meal.LUNCH -> {
                val k = 0.45f + 0.55f * a
                c.save(); c.translate(x, y - s * 0.18f); c.scale(k, k)
                ui.color = tn(hex("#E8B48F"))
                tmpPath.reset(); tmpPath.moveTo(s * 0.3f, 0f); tmpPath.lineTo(s * 0.52f, -s * 0.15f); tmpPath.lineTo(s * 0.52f, s * 0.15f); tmpPath.close()
                c.drawPath(tmpPath, ui)
                c.oval(0f, 0f, s * 0.38f, s * 0.16f, ui)
                uiLine.color = tn(hex("#B9805F")); uiLine.strokeWidth = s * 0.03f
                for (j in -1..1) c.drawLine(j * s * 0.12f - s * 0.03f, -s * 0.1f, j * s * 0.12f + s * 0.03f, s * 0.1f, uiLine)
                ui.color = tn(hex("#4A4F5C")); c.drawCircle(-s * 0.24f, -s * 0.03f, s * 0.03f, ui)
                c.restore()
            }
            Meal.DINNER -> {
                val k = 0.45f + 0.55f * a
                c.save(); c.translate(x, y - s * 0.22f); c.scale(k, k)
                tmpPath.reset(); tmpPath.moveTo(0f, -s * 0.3f); tmpPath.quadTo(s * 0.36f, s * 0.12f, s * 0.26f, s * 0.2f)
                tmpPath.lineTo(-s * 0.26f, s * 0.2f); tmpPath.quadTo(-s * 0.36f, s * 0.12f, 0f, -s * 0.3f); tmpPath.close()
                ui.color = tn(hex("#F6F3EC")); c.drawPath(tmpPath, ui)
                ui.color = tn(hex("#3A4046")); c.drawRoundRect(-s * 0.13f, s * 0.02f, s * 0.13f, s * 0.21f, s * 0.02f, s * 0.02f, ui)
                c.restore()
            }
        }
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
            val chip = "${st.rarity.label} · Grafite"
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
        c.drawText("Escamas grafite que prateiam com a idade, olhos azuis", l + pad, y, txt); y += 22f * dp
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
