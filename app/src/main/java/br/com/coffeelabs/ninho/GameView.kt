package br.com.coffeelabs.ninho

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class GameView(ctx: Context, private val st: DragonState, private val host: Host) : View(ctx) {

    interface Host {
        fun askNewEgg()
        fun saveState()
        fun onDarkSky(dark: Boolean)
    }

    private enum class Mode { IDLE, WALK, GO_EAT, EAT, PLAY, GO_SLEEP, SLEEP }

    private val dp = resources.displayMetrics.density
    private val forest = ForestRenderer()
    private val dragon = DragonRenderer(DragonColors.SILVER)
    private val fx = Particles(dp)
    private val pose = Pose()
    private val rnd = Random()
    private val pi = PI.toFloat()

    // ---------------------------------------------------------------- layout
    private var vw = 0f
    private var vh = 0f
    private var insetTop = 0f
    private var insetBottom = 0f
    private var hudTop = 0f
    private var hudBottom = 0f
    private var dockTop = 0f
    private var dockBottom = 0f
    private var frontY = 0f
    private var backY = 0f
    private var horizonY = 0f
    private var baseSize = 0f
    private var homeX = 0f
    private val nestDepth = 0.82f

    // ---------------------------------------------------------------- tempo
    private var running = false
    private var lastNs = 0L
    private var t = 0f
    private var saveTimer = 0f

    // ---------------------------------------------------------------- dragão
    private var posX = 0f
    private var posD = 1f
    private var tgtX = 0f
    private var tgtD = 1f
    private var mode = Mode.IDLE
    private var modeTimer = 2f
    private var face = 1f
    private var faceTarget = 1f
    private var walking = false
    private var walkAmt = 0f
    private var walkPhase = 0f
    private var jumpY = 0f
    private var jumpV = 0f
    private var squash = 0f
    private var squashV = 0f
    private var sleepAmt = 0f
    private var sleepDip = 0f
    private var sleepPokes = 0
    private var exprOverride = Expr.CALM
    private var exprTimer = 0f
    private var blinkTimer = 3f
    private var blinkPhase = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var lookTX = 0f
    private var lookTY = 0f
    private var tilt = 0f
    private var tiltTarget = 0f
    private var flap = 0f
    private var wingPhase = 0f
    private var wiggle = 0f
    private var zzzTimer = 0f
    private var drawnStage: Stage = st.stage
    private var sizeCur = 0f

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
    private var foodD = 1f
    private var foodAmt = 1f
    private var eatTimer = 0f

    // bolinha
    private var ballOn = false
    private var ballX = 0f
    private var ballH = 0f
    private var ballVX = 0f
    private var ballVH = 0f
    private var ballD = 1f
    private var ballRot = 0f
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
    private var lastHour = 12f

    // UI
    private var toastMsg = ""
    private var toastT = 0f
    private var diaryOpen = false
    private val diaryRects = ArrayList<Pair<RectF, Int>>()
    private var darkReported: Boolean? = null

    private val ui = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val uiLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val txtB = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val heartPath = Path().apply {
        moveTo(0f, 0.35f)
        cubicTo(-0.05f, 0.3f, -0.5f, 0.05f, -0.5f, -0.2f)
        cubicTo(-0.5f, -0.45f, -0.15f, -0.55f, 0f, -0.3f)
        cubicTo(0.15f, -0.55f, 0.5f, -0.45f, 0.5f, -0.2f)
        cubicTo(0.5f, 0.05f, 0.05f, 0.3f, 0f, 0.35f)
        close()
    }
    private val tmpPath = Path()
    private val rect = RectF()

    private val cText = hex("#5B6275")
    private val cSub = hex("#8A90A2")
    private val cFood = hex("#F6D9C8"); private val cFoodI = hex("#C98B6E")
    private val cPlay = hex("#CFE5D3"); private val cPlayI = hex("#6FA07D")
    private val cSleep = hex("#DDD6F0"); private val cSleepI = hex("#8A7FBF")
    private val cDiary = hex("#D3E3F1"); private val cDiaryI = hex("#6E95BD")

    init {
        isClickable = true
        val now = System.currentTimeMillis()
        if (st.isSleeping(now)) { mode = Mode.SLEEP; sleepAmt = 1f; posD = nestDepth }
    }

    // ================================================================ ciclo
    fun resume() {
        running = true
        lastNs = 0L
        postInvalidateOnAnimation()
    }

    fun pause() {
        running = false
    }

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top.toFloat(); insetBottom = bottom.toFloat()
        relayout()
    }

    fun onNewDragon() {
        drawnStage = st.stage
        evolveT = -1f
        mode = Mode.IDLE; modeTimer = 2f
        posX = homeX; posD = nestDepth
        foodMeal = null; ballOn = false
        sizeCur = targetSize()
        fx.clear()
        toast("Um ovo prateado apareceu no ninho!")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        vw = w.toFloat(); vh = h.toFloat()
        relayout()
    }

    private fun relayout() {
        if (vw <= 0f) return
        hudTop = insetTop + 8f * dp
        hudBottom = hudTop + 112f * dp
        dockBottom = vh - insetBottom - 10f * dp
        dockTop = dockBottom - 92f * dp
        frontY = dockTop - 20f * dp
        backY = frontY - 0.085f * vh
        horizonY = backY - 0.11f * vh
        baseSize = minOf(0.44f * vh, vw * 0.9f / DragonRenderer.WIDTH_FACTOR, frontY - hudBottom - 24f * dp)
        homeX = vw / 2f
        forest.resize(vw, vh, horizonY, backY, frontY, dp)
        if (posX == 0f) { posX = homeX; tgtX = homeX }
        if (sizeCur == 0f) sizeCur = targetSize()
    }

    private fun groundY(d: Float) = lerp(backY, frontY, d)
    private fun depthScale(d: Float) = lerp(0.72f, 1f, d)
    private fun stageMult(s: Stage) = when (s) {
        Stage.EGG -> 0.42f; Stage.BABY -> 0.62f; Stage.CHILD -> 0.74f
        Stage.TEEN -> 0.87f; Stage.ADULT -> 1f; Stage.ELDER -> 0.98f
    }
    private fun targetSize() = baseSize * stageMult(drawnStage)
    private fun sizeAt(d: Float) = sizeCur * depthScale(d)
    private fun rangeX(d: Float): Float {
        val half = sizeAt(d) * DragonRenderer.WIDTH_FACTOR * 0.5f
        return max(0f, vw / 2f - half + 0.02f * vw)
    }
    private fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    // ================================================================ atualização
    private fun update(dt: Float) {
        val now = System.currentTimeMillis()
        t += dt
        st.simulateTo(now)
        val hour = Clock.hourOf(now)
        lastHour = hour
        forest.updateSky(hour)
        val dark = forest.night > 0.5f
        if (dark != darkReported) { darkReported = dark; host.onDarkSky(dark) }

        if (!st.exists) { fx.update(dt); toastT -= dt; return }

        st.evolvedTo?.let { startEvolve(it); st.evolvedTo = null; host.saveState() }
        if (st.missed.isNotEmpty()) {
            if (st.stage != Stage.EGG && evolveT < 0f) {
                toast(if (st.missed.size > 1) "${st.name} sentiu falta de algumas refeições…"
                else "${st.name} sentiu falta do ${st.missed[0].label.lowercase()}…")
            }
            st.missed.clear()
        }

        updateEvolve(dt)
        sizeCur = approach(sizeCur, targetSize(), 2.6f, dt)
        petBudget = min(10f, petBudget + dt / 12f)
        if (exprTimer > 0f) exprTimer -= dt
        // piscar
        blinkTimer -= dt
        if (blinkTimer <= 0f) { blinkPhase = 0.16f; blinkTimer = rf(2.4f, 5.8f) }
        if (blinkPhase > 0f) blinkPhase = max(0f, blinkPhase - dt)

        if (drawnStage == Stage.EGG) updateEgg(dt) else updateDragon(dt, now)

        fx.update(dt)
        toastT -= dt
        saveTimer += dt
        if (saveTimer > 30f) { saveTimer = 0f; host.saveState() }
    }

    private fun updateEgg(dt: Float) {
        eggWobbleV += (-70f * eggWobble - 5f * eggWobbleV) * dt
        eggWobble += eggWobbleV * dt
        val prog = st.progress()
        if (rnd.nextFloat() < dt * (0.12f + prog * 0.9f)) eggWobbleV += rf(-1f, 1f) * (18f + prog * 70f)
        if (touching && onEgg && moved > 10f * dp) {
            // esfregar o ovo
            if (rnd.nextFloat() < dt * 5f) {
                val ey = groundY(nestDepth) - sizeAt(nestDepth) * 0.5f
                fx.spawn(Particles.SPARKLE, homeX + rf(-1f, 1f) * sizeAt(nestDepth) * 0.3f, ey + rf(-1f, 1f) * sizeAt(nestDepth) * 0.3f,
                    0f, -30f * dp, 0.9f, 9f * dp, hex("#BFD8F2"), rf(-90f, 90f))
            }
        }
    }

    private fun updateDragon(dt: Float, now: Long) {
        val sleepingNow = st.isSleeping(now)
        if (sleepingNow) {
            if (mode != Mode.SLEEP && mode != Mode.GO_SLEEP && mode != Mode.EAT) {
                endPlay(false)
                mode = Mode.GO_SLEEP
            }
        } else if (mode == Mode.SLEEP || mode == Mode.GO_SLEEP) {
            mode = Mode.IDLE; modeTimer = 2.5f
            setExpr(Expr.JOY, 1.6f)
            sleepPokes = 0
            toast("${st.name} acordou! Bom dia.")
        }

        walking = false
        when (mode) {
            Mode.IDLE -> {
                modeTimer -= dt
                if (modeTimer <= 0f) decideIdle()
            }
            Mode.WALK -> if (moveTo(tgtX, tgtD, 1f, dt)) { mode = Mode.IDLE; modeTimer = rf(2.5f, 6f) }
            Mode.GO_EAT -> {
                val side = if (foodX >= posX) -1f else 1f
                val target = foodX + side * sizeAt(foodD) * 0.42f
                if (moveTo(target, foodD, 1.2f, dt)) {
                    mode = Mode.EAT; eatTimer = 3.4f
                    faceTarget = if (foodX >= posX) 1f else -1f
                    setExpr(Expr.SURPRISED, 0.4f)
                }
            }
            Mode.EAT -> {
                eatTimer -= dt
                foodAmt = clamp01(eatTimer / 3.0f)
                if (rnd.nextFloat() < dt * 8f) {
                    val s = sizeAt(posD)
                    fx.spawn(Particles.CRUMB, foodX + rf(-0.1f, 0.1f) * s, groundY(foodD) - s * 0.12f,
                        rf(-60f, 60f) * dp, rf(-220f, -120f) * dp, 0.7f, rf(1.5f, 3f) * dp, foodColor())
                }
                if (eatTimer <= 0f) {
                    foodMeal = null
                    mode = Mode.IDLE; modeTimer = 2.5f
                    setExpr(Expr.BLISS, 1.8f)
                    hearts(3)
                    squashV += 2f
                }
            }
            Mode.PLAY -> updatePlay(dt)
            Mode.GO_SLEEP -> if (moveTo(homeX, nestDepth, 0.8f, dt)) { mode = Mode.SLEEP; setExpr(Expr.SLEEPY, 1.2f) }
            Mode.SLEEP -> {
                zzzTimer -= dt
                if (zzzTimer <= 0f) {
                    zzzTimer = 1.7f
                    val (hx, hy) = headScreen()
                    fx.spawn(Particles.ZZZ, hx + face * sizeAt(posD) * 0.1f, hy - sizeAt(posD) * 0.12f,
                        face * 10f * dp, -28f * dp, 2.6f, 22f * dp, hex("#E9ECF8"))
                }
            }
        }

        sleepAmt = approach(sleepAmt, if (mode == Mode.SLEEP) 1f else 0f, 2.2f, dt)
        sleepDip = max(0f, sleepDip - dt * 0.8f)
        walkAmt = approach(walkAmt, if (walking) 1f else 0f, 8f, dt)
        face = approach(face, faceTarget, 9f, dt)
        // pulo
        val s = sizeAt(posD)
        if (jumpY > 0f || jumpV > 0f) {
            jumpY += jumpV * dt
            jumpV -= s * 7f * dt
            if (jumpY <= 0f) {
                jumpY = 0f
                if (jumpV < -s * 0.6f) squashV += 3.5f
                jumpV = 0f
            }
            flap = 1f
        }
        squashV += (-170f * squash - 13f * squashV) * dt
        squash = (squash + squashV * dt).coerceIn(-0.22f, 0.22f)
        flap = max(0f, flap - dt * 0.9f)
        wiggle = max(0f, wiggle - dt * 1.5f)
        tilt = approach(tilt, tiltTarget, 4f, dt)
        lookX = approach(lookX, lookTX, 7f, dt)
        lookY = approach(lookY, lookTY, 7f, dt)
        if (!touching && rnd.nextFloat() < dt * 0.25f) { lookTX = rf(-0.6f, 0.6f); lookTY = rf(-0.4f, 0.3f) }
        if (rnd.nextFloat() < dt * 0.2f) tiltTarget *= 0.3f
        wingPhase += dt * (1.3f + 7f * flap) * 2f * pi
        kickCd -= dt

        // carinho contínuo (esfregar)
        if (touching && onDragon && moved > 10f * dp) {
            if (mode != Mode.SLEEP) setExpr(Expr.LOVE, 0.35f)
        }
        // abraço (segurar)
        if (touching && onDragon && !hugged && moved < 10f * dp && t - downT > 0.6f) {
            hugged = true
            hug()
        }
    }

    private fun decideIdle() {
        val r = rnd.nextFloat()
        val m = st.mood()
        when {
            st.energy < 18f && r < 0.5f -> {
                setExpr(Expr.SLEEPY, 2.5f)
                modeTimer = 4f
            }
            r < 0.55f -> {
                tgtD = rf(0.3f, 1f)
                val g = (rnd.nextGaussian() * 0.6).toFloat().coerceIn(-1f, 1f)
                tgtX = homeX + g * rangeX(tgtD)
                mode = Mode.WALK
            }
            r < 0.72f -> {
                tiltTarget = rf(-0.18f, 0.18f)
                lookTX = rf(-1f, 1f); lookTY = rf(-0.5f, 0.5f)
                modeTimer = 2.5f
            }
            r < 0.84f && m > 62f -> {
                hop(0.8f); setExpr(Expr.JOY, 1.2f); sparkles(4)
                modeTimer = 2.5f
            }
            r < 0.92f && m < 38f -> {
                setExpr(Expr.SAD, 2.5f)
                val (hx, hy) = headScreen()
                fx.spawn(Particles.PUFF, hx, hy - sizeAt(posD) * 0.25f, 0f, -20f * dp, 2f, 9f * dp, hex("#C9CEDD"))
                modeTimer = 3f
            }
            else -> { faceTarget = -faceTarget; modeTimer = 2.5f }
        }
    }

    private fun moveTo(txp: Float, td: Float, speedMul: Float, dt: Float): Boolean {
        val s = sizeAt(posD)
        val dxp = txp - posX
        val ddp = (td - posD) * (frontY - backY) * 1.6f
        val dist = hypot(dxp, ddp)
        if (dist < 3f * dp) return true
        val step = min(dist, s * 0.55f * speedMul * dt)
        posX += dxp / dist * step
        posD += (td - posD) * (step / dist)
        walkPhase += step / (s * 0.16f)
        if (abs(dxp) > 2f * dp) faceTarget = sign(dxp)
        walking = true
        return false
    }

    private fun hop(power: Float) {
        if (jumpY <= 0f) { jumpV = sizeAt(posD) * 1.6f * power; jumpY = 0.01f; squashV -= 2f }
        flap = 1f
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
        return when {
            m > 45f -> Expr.CALM
            m > 30f -> Expr.NEUTRAL
            else -> Expr.SAD
        }
    }

    /** Centro aproximado da cabeça, em pixels. */
    private fun headScreen(): Pair<Float, Float> {
        val b = DragonRenderer.build(drawnStage)
        val s = sizeAt(posD) / 100f
        val hxU = lerp(b.headX, b.headX + 7f, sleepAmt) + 4f
        val hyU = lerp(b.headY, -b.headR, sleepAmt)
        return Pair(posX + sign(face).let { if (it == 0f) 1f else it } * hxU * s, groundY(posD) - jumpY + hyU * s)
    }

    private fun hearts(n: Int) {
        val (hx, hy) = headScreen()
        val s = sizeAt(posD)
        for (i in 0 until n) {
            fx.spawn(Particles.HEART, hx + rf(-0.15f, 0.15f) * s, hy - s * 0.15f, rf(-30f, 30f) * dp, rf(-90f, -60f) * dp,
                rf(1.2f, 1.8f), rf(14f, 22f) * dp, if (rnd.nextBoolean()) hex("#F2B8C6") else hex("#F6CAD3"))
        }
    }

    private fun sparkles(n: Int, cx: Float = posX, cy: Float = groundY(posD) - sizeAt(posD) * 0.5f, spread: Float = sizeAt(posD) * 0.5f) {
        for (i in 0 until n) {
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
                posX = homeX; posD = nestDepth
                sizeCur = baseSize * 0.42f
                val cy = groundY(nestDepth) - sizeCur * 0.5f
                for (i in 0 until 10) {
                    val a = rf(-pi, 0f)
                    fx.spawn(Particles.SHELL, homeX + rf(-0.15f, 0.15f) * sizeCur, cy, cos(a) * rf(120f, 260f) * dp,
                        sin(a) * rf(250f, 420f) * dp, 1.4f, rf(10f, 16f) * dp, hex("#D6DBE3"), rf(-300f, 300f))
                }
                sparkles(10, homeX, cy, sizeCur * 0.6f)
                setExpr(Expr.SURPRISED, 1.2f)
                mode = Mode.IDLE; modeTimer = 3f
                toast("${st.name} nasceu! Bem-vindo ao ninho.")
            } else {
                sparkles(12)
                setExpr(Expr.JOY, 2f)
                toast(when (drawnStage) {
                    Stage.ELDER -> "${st.name} virou um ancião sábio."
                    else -> "${st.name} cresceu: agora é ${drawnStage.label.lowercase()}!"
                })
            }
        }
        if (evolveSwapped && evolveT > swapAt() + 1.3f) evolveT = -1f
    }

    private fun evolveGlow(): Float {
        if (evolveT < 0f) return 0f
        return if (!evolveSwapped) smooth(evolveT / swapAt()) else 1f - smooth((evolveT - swapAt()) / 1.3f)
    }

    // ---------------------------------------------------------------- brincar
    private fun startPlay() {
        ballOn = true
        ballD = posD.coerceIn(0.45f, 1f)
        val s = sizeAt(ballD)
        ballX = (posX + face * s * 0.7f).coerceIn(vw * 0.15f, vw * 0.85f)
        ballH = vh * 0.3f
        ballVX = 0f; ballVH = 0f
        playTimer = 11f
        mode = Mode.PLAY
        st.energy -= 8f
        st.happiness += 12f
        st.clampStats()
        setExpr(Expr.SURPRISED, 0.6f)
    }

    private fun endPlay(celebrate: Boolean) {
        if (!ballOn) return
        ballOn = false
        if (celebrate) {
            sparkles(5, ballX, groundY(ballD) - ballR() - ballH, ballR() * 2f)
            setExpr(Expr.BLISS, 1.6f); hearts(2)
            toast("Que brincadeira boa!")
        }
        if (mode == Mode.PLAY) { mode = Mode.IDLE; modeTimer = 2f }
    }

    private fun ballR() = baseSize * 0.075f * depthScale(ballD)

    private fun updatePlay(dt: Float) {
        playTimer -= dt
        val r = ballR()
        ballVH -= vh * 2.2f * dt
        ballH += ballVH * dt
        if (ballH < 0f) {
            ballH = 0f
            ballVH = -ballVH * 0.55f
            if (abs(ballVH) < 60f * dp) ballVH = 0f
        }
        ballX += ballVX * dt
        ballVX *= (1f - (if (ballH <= 0f) 1.6f else 0.3f) * dt)
        if (ballX < r) { ballX = r; ballVX = abs(ballVX) * 0.7f }
        if (ballX > vw - r) { ballX = vw - r; ballVX = -abs(ballVX) * 0.7f }
        ballRot += ballVX * dt / r * 57.3f

        val s = sizeAt(posD)
        val side = if (ballX >= posX) -1f else 1f
        val target = (ballX + side * s * 0.38f).coerceIn(homeX - rangeX(ballD) - s * 0.2f, homeX + rangeX(ballD) + s * 0.2f)
        val arrived = moveTo(target, ballD, 1.5f, dt)
        if (arrived) faceTarget = if (ballX >= posX) 1f else -1f
        val noseX = posX + face * s * 0.38f
        if (kickCd <= 0f && abs(ballX - noseX) < s * 0.25f && ballH < s * 0.3f) {
            kickCd = 0.7f
            val dir = if (ballX >= posX) 1f else -1f
            ballVX = dir * rf(0.9f, 1.6f) * s * 1.8f
            ballVH = s * rf(1.4f, 2.4f)
            setExpr(Expr.JOY, 0.9f)
            if (rnd.nextFloat() < 0.35f) hop(0.5f)
            fx.spawn(Particles.SPARKLE, ballX, groundY(ballD) - r, 0f, -40f * dp, 0.6f, 10f * dp, hex("#FFF4CF"), 90f)
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
                onEgg = pressedBtn < 0 && st.exists && drawnStage == Stage.EGG && hitEgg(ex, ey)
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
                    if (rubAcc > 40f * dp) { rubAcc = 0f; eggWobbleV += rf(-1f, 1f) * 25f; st.growthMs += 3000.0 }
                }
                if (onDragon && moved > 10f * dp && hitDragon(ex, ey)) rub(ex, ey, d, d / dtm)
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                val dur = t - downT
                if (diaryOpen) { if (moved < 12f * dp) diaryTap(ex, ey); return true }
                if (pressedBtn >= 0) {
                    if (buttonAt(ex, ey) == pressedBtn) onButton(pressedBtn)
                } else if (onEgg && moved < 12f * dp) {
                    tapEgg()
                } else if (onDragon && moved < 12f * dp && !hugged && dur < 0.45f) {
                    tapDragon()
                } else if (!onDragon && !onEgg && moved < 12f * dp) {
                    tapGround(ex, ey)
                }
                pressedBtn = -1; onDragon = false; onEgg = false
                lookTX *= 0.3f; lookTY *= 0.3f
            }
            MotionEvent.ACTION_CANCEL -> { touching = false; pressedBtn = -1; onDragon = false; onEgg = false }
        }
        return true
    }

    private fun lookAtFinger(px: Float, py: Float) {
        if (!st.exists || drawnStage == Stage.EGG) return
        val (hx, hy) = headScreen()
        val dx = px - hx; val dy = py - hy
        val d = max(1f, hypot(dx, dy))
        lookTX = (dx / d) * sign(face).let { if (it == 0f) 1f else it }
        lookTY = (dy / d) * 0.8f
        if (abs(dx) > sizeAt(posD) * 0.3f && mode != Mode.SLEEP && mode != Mode.EAT && !walking) faceTarget = sign(dx)
    }

    private fun hitDragon(px: Float, py: Float): Boolean {
        val s = sizeAt(posD)
        val cx = posX; val cy = groundY(posD) - jumpY - s * 0.48f
        val rx = s * 0.55f; val ry = s * 0.56f
        val a = (px - cx) / rx; val b = (py - cy) / ry
        return a * a + b * b <= 1f
    }

    private fun hitEgg(px: Float, py: Float): Boolean {
        val s = sizeAt(nestDepth)
        val cx = homeX; val cy = groundY(nestDepth) - s * 0.48f
        val a = (px - cx) / (s * 0.6f); val b = (py - cy) / (s * 0.65f)
        return a * a + b * b <= 1f
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
        eggWobbleV += if (rnd.nextBoolean()) 60f else -60f
        st.growthMs += 15_000.0
        sparkles(3, homeX, groundY(nestDepth) - sizeAt(nestDepth) * 0.5f, sizeAt(nestDepth) * 0.35f)
        haptic()
        if (rnd.nextFloat() < 0.25f) toast(listOf("O ovo se remexeu!", "Dá para sentir um calorzinho…", "Toc, toc… tem alguém aí?")[rnd.nextInt(3)])
    }

    private fun tapDragon() {
        if (mode == Mode.SLEEP) {
            sleepPokes++
            sleepDip = 1f
            setExpr(Expr.ANNOYED, 1.3f)
            val (hx, hy) = headScreen()
            fx.spawn(Particles.PUFF, hx, hy - sizeAt(posD) * 0.2f, 0f, -18f * dp, 1.4f, 7f * dp, hex("#C9CEDD"))
            if (sleepPokes >= 3) { st.happiness -= 0.5f; st.clampStats(); toast("Shh… ${st.name} está dormindo."); sleepPokes = 0 }
            return
        }
        tapTimes.add(t)
        tapTimes.removeAll { t - it > 1.6f }
        if (tapTimes.size >= 6) {
            setExpr(Expr.ANNOYED, 1.8f)
            st.happiness -= 1f; st.clampStats()
            faceTarget = -faceTarget
            if (t - annoyToastT > 8f) { annoyToastT = t; toast("Ei, calma! Cutucadas demais.") }
            tapTimes.clear()
            return
        }
        if (t - lastTapT < 0.32f) {
            hop(1f); sparkles(5); setExpr(Expr.JOY, 1f)
        } else {
            hearts(if (rnd.nextBoolean()) 1 else 2)
            squashV += 2.5f
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
            fx.spawn(if (note) Particles.NOTE else Particles.HEART, px + rf(-10f, 10f) * dp, py - 20f * dp,
                rf(-30f, 30f) * dp, -70f * dp, 1.2f, (if (note) 16f else 13f) * dp,
                if (note) hex("#C9C0EA") else hex("#F2B8C6"))
            if (petBudget > 0.1f) { st.happiness += 0.35f; petBudget -= 0.1f; st.clampStats() }
        }
    }

    private fun hug() {
        if (mode == Mode.SLEEP) { setExpr(Expr.SLEEP, 1f); hearts(1); return }
        setExpr(Expr.LOVE, 2.4f)
        squashV += 3f
        val (hx, hy) = headScreen()
        fx.spawn(Particles.HEART, hx, hy - sizeAt(posD) * 0.25f, 0f, -50f * dp, 2f, 40f * dp, hex("#F2B8C6"))
        if (petBudget >= 2f) { st.happiness += 4f; petBudget -= 2f; st.clampStats() }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun tapGround(px: Float, py: Float) {
        if (!st.exists || drawnStage == Stage.EGG) return
        if (py < backY - 0.05f * vh || py > dockTop || py < hudBottom) return
        fx.spawn(Particles.RIPPLE, px, py, 0f, 0f, 0.8f, 26f * dp, 0xFFFFFFFF.toInt())
        if (mode == Mode.SLEEP || mode == Mode.GO_SLEEP || mode == Mode.EAT || mode == Mode.PLAY) return
        tgtD = ((py - backY) / (frontY - backY)).coerceIn(0.2f, 1f)
        val r = rangeX(tgtD)
        tgtX = px.coerceIn(homeX - r, homeX + r)
        mode = Mode.WALK
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
                    else {
                        val nx = Clock.nextMeal(h)
                        toast("Ainda não é hora. ${nx.label}: das ${nx.start}h às ${nx.end}h.")
                    }
                    return
                }
                endPlay(false)
                st.feed(m, now)
                host.saveState()
                foodMeal = m
                foodAmt = 1f
                foodD = posD.coerceIn(0.55f, 1f)
                val s = sizeAt(foodD)
                val dir = if (posX > homeX) -1f else 1f
                foodX = (posX + dir * s * 0.75f).coerceIn(s * 0.3f, vw - s * 0.3f)
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

    // ---------------------------------------------------------------- diário
    private fun diaryTap(px: Float, py: Float) {
        val hit = diaryRects.firstOrNull { it.first.contains(px, py) }?.second
        val now = System.currentTimeMillis()
        when (hit) {
            null -> diaryOpen = false
            0 -> diaryOpen = false
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
        drawWorld(c)
        drawHud(c)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawWorld(c: Canvas) {
        forest.drawBack(c, lastHour, t)
        val night = forest.night
        val nestY = groundY(nestDepth)
        val nestW = baseSize * 0.8f * depthScale(nestDepth)
        if (!st.exists) {
            dragon.drawNest(c, homeX, nestY, nestW, night, false)
            dragon.drawNest(c, homeX, nestY, nestW, night, true)
            forest.drawFront(c, t)
            return
        }
        if (drawnStage == Stage.EGG) {
            dragon.drawNest(c, homeX, nestY, nestW, night, false)
            val es = sizeAt(nestDepth)
            var wob = eggWobble
            if (evolveT >= 0f && !evolveSwapped) wob += sin(t * 42f) * 9f * smooth(evolveT / swapAt())
            val glow = max(smooth((st.progress() - 0.6f) / 0.4f) * (0.6f + 0.4f * sin(t * 2.4f)), evolveGlow())
            dragon.drawEgg(c, homeX, nestY + es * 0.02f, es, wob, st.progress(), glow, night)
            dragon.drawNest(c, homeX, nestY, nestW, night, true)
            fx.draw(c)
            drawEvolveFlash(c, homeX, nestY - es * 0.45f, es)
            forest.drawFront(c, t)
            return
        }
        val inNest = abs(posX - homeX) < nestW * 0.35f && abs(posD - nestDepth) < 0.08f
        val behind = posD < nestDepth - 0.03f && !inNest
        if (!behind) {
            dragon.drawNest(c, homeX, nestY, nestW, night, false)
            if (!inNest) dragon.drawNest(c, homeX, nestY, nestW, night, true)
        }
        // itens atrás do dragão
        if (foodMeal != null && foodD <= posD) drawFood(c, night)
        if (ballOn && ballD <= posD) drawBall(c, night)
        drawDragon(c, night)
        if (foodMeal != null && foodD > posD) drawFood(c, night)
        if (ballOn && ballD > posD) drawBall(c, night)
        if (inNest) dragon.drawNest(c, homeX, nestY, nestW, night, true)
        if (behind) {
            dragon.drawNest(c, homeX, nestY, nestW, night, false)
            dragon.drawNest(c, homeX, nestY, nestW, night, true)
        }
        fx.draw(c)
        val s = sizeAt(posD)
        drawEvolveFlash(c, posX, groundY(posD) - s * 0.5f, s)
        forest.drawFront(c, t)
    }

    private fun drawDragon(c: Canvas, night: Float) {
        val s = sizeAt(posD)
        val gy = groundY(posD)
        val k = 1f - (jumpY / (s * 1.2f)).coerceIn(0f, 0.6f)
        ui.color = withAlpha(lerpColor(hex("#5E7A5A"), DragonColors.NIGHT, night), 0.16f * k)
        c.oval(posX, gy + s * 0.005f, s * 0.42f * k * (1f + sleepAmt * 0.25f), s * 0.05f * k, ui)
        pose.t = t
        pose.breath = sin(t * 2f * pi / lerp(3.4f, 4.6f, sleepAmt))
        pose.walk = walkAmt
        pose.walkPhase = walkPhase
        pose.sleep = sleepAmt * (1f - 0.35f * sleepDip)
        pose.blink = if (blinkPhase > 0f) sin((1f - blinkPhase / 0.16f) * pi) else 0f
        pose.expr = currentExpr()
        pose.lookX = lookX; pose.lookY = lookY
        pose.headTilt = tilt
        pose.flap = flap
        pose.wingPhase = wingPhase
        pose.squash = squash
        pose.wiggle = wiggle
        pose.night = night
        dragon.draw(c, posX, gy - jumpY, s, face, drawnStage, pose)
    }

    private fun drawEvolveFlash(c: Canvas, cx: Float, cy: Float, s: Float) {
        val g = evolveGlow()
        if (g <= 0.01f) return
        ui.shader = RadialGradient(cx, cy, s * 0.95f, intArrayOf(withAlpha(0xFFFFFFFF.toInt(), 0.85f * g), withAlpha(hex("#CFE2F7"), 0.5f * g), withAlpha(hex("#CFE2F7"), 0f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.95f, ui)
        ui.shader = null
    }

    private fun foodColor(): Int = when (foodMeal) {
        Meal.BREAKFAST -> hex("#D9A7C7"); Meal.LUNCH -> hex("#F2C0A8"); else -> hex("#EBA9A9")
    }

    private fun drawFood(c: Canvas, night: Float) {
        val m = foodMeal ?: return
        val s = baseSize * 0.2f * depthScale(foodD)
        val gy = groundY(foodD)
        fun tn(col: Int) = lerpColor(col, DragonColors.NIGHT, night * 0.42f)
        ui.color = tn(hex("#BFD9B0")); c.oval(foodX, gy - s * 0.04f, s * 0.6f, s * 0.17f, ui)
        uiLine.color = tn(hex("#9CC08E")); uiLine.strokeWidth = 1.5f * dp
        c.drawLine(foodX - s * 0.5f, gy - s * 0.04f, foodX + s * 0.5f, gy - s * 0.04f, uiLine)
        val a = foodAmt
        if (a <= 0.02f) return
        when (m) {
            Meal.BREAKFAST -> {
                val cols = intArrayOf(hex("#D9A7C7"), hex("#B9ACDD"), hex("#E8B4C4"), hex("#C7B3E3"), hex("#DDA0B8"))
                val pos = floatArrayOf(-0.2f, -0.1f, 0f, -0.16f, 0.2f, -0.1f, -0.1f, -0.28f, 0.1f, -0.27f)
                val n = ceil(a * 5f).toInt()
                for (i in 0 until n) {
                    ui.color = tn(cols[i]); c.drawCircle(foodX + pos[i * 2] * s, gy + pos[i * 2 + 1] * s, s * 0.11f, ui)
                    ui.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f); c.drawCircle(foodX + pos[i * 2] * s - s * 0.035f, gy + pos[i * 2 + 1] * s - s * 0.035f, s * 0.03f, ui)
                }
            }
            Meal.LUNCH -> {
                val k = 0.4f + 0.6f * a
                c.save(); c.translate(foodX, gy - s * 0.17f); c.scale(k, k)
                ui.color = tn(hex("#F2C0A8"))
                tmpPath.reset(); tmpPath.moveTo(s * 0.3f, 0f); tmpPath.lineTo(s * 0.5f, -s * 0.14f); tmpPath.lineTo(s * 0.5f, s * 0.14f); tmpPath.close()
                c.drawPath(tmpPath, ui)
                c.oval(0f, 0f, s * 0.36f, s * 0.16f, ui)
                uiLine.color = tn(hex("#D99A80")); uiLine.strokeWidth = 1.4f * dp
                c.drawLine(-s * 0.05f, -s * 0.12f, -s * 0.05f, s * 0.12f, uiLine)
                ui.color = tn(hex("#6E7486")); c.drawCircle(-s * 0.22f, -s * 0.03f, s * 0.03f, ui)
                c.restore()
            }
            Meal.DINNER -> {
                val k = 0.4f + 0.6f * a
                c.save(); c.translate(foodX, gy - s * 0.22f); c.scale(k, k)
                ui.color = tn(hex("#EBA9A9")); c.drawCircle(0f, 0f, s * 0.22f, ui)
                ui.color = withAlpha(0xFFFFFFFF.toInt(), 0.45f); c.oval(-s * 0.08f, -s * 0.08f, s * 0.05f, s * 0.08f, ui)
                uiLine.color = tn(hex("#A98A6E")); uiLine.strokeWidth = 2f * dp
                c.drawLine(0f, -s * 0.2f, s * 0.03f, -s * 0.3f, uiLine)
                ui.color = tn(hex("#AFCDA4")); c.oval(s * 0.1f, -s * 0.27f, s * 0.08f, s * 0.04f, ui)
                c.restore()
            }
        }
    }

    private fun drawBall(c: Canvas, night: Float) {
        val r = ballR()
        val gy = groundY(ballD)
        val k = 1f - (ballH / (vh * 0.3f)).coerceIn(0f, 0.7f)
        ui.color = withAlpha(lerpColor(hex("#5E7A5A"), DragonColors.NIGHT, night), 0.15f * k)
        c.oval(ballX, gy, r * 0.9f * k, r * 0.25f * k, ui)
        val cy = gy - r - ballH
        c.save(); c.translate(ballX, cy); c.rotate(ballRot)
        ui.color = lerpColor(hex("#F2C9D4"), DragonColors.NIGHT, night * 0.42f); c.drawCircle(0f, 0f, r, ui)
        uiLine.color = lerpColor(hex("#DDA3B3"), DragonColors.NIGHT, night * 0.42f); uiLine.strokeWidth = r * 0.1f
        tmpPath.reset(); tmpPath.moveTo(-r * 0.8f, -r * 0.5f); tmpPath.quadTo(0f, r * 0.2f, r * 0.8f, -r * 0.5f); c.drawPath(tmpPath, uiLine)
        tmpPath.reset(); tmpPath.moveTo(-r * 0.9f, r * 0.1f); tmpPath.quadTo(0f, r * 0.8f, r * 0.85f, r * 0.2f); c.drawPath(tmpPath, uiLine)
        tmpPath.reset(); tmpPath.moveTo(-r * 0.3f, -r * 0.95f); tmpPath.quadTo(r * 0.2f, 0f, -r * 0.1f, r * 0.95f); c.drawPath(tmpPath, uiLine)
        uiLine.color = lerpColor(hex("#C995A6"), DragonColors.NIGHT, night * 0.42f); uiLine.strokeWidth = 1.3f * dp
        c.drawCircle(0f, 0f, r, uiLine)
        c.restore()
    }

    // ---------------------------------------------------------------- HUD
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private fun card(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float, night: Float) {
        ui.color = withAlpha(lerpColor(0xFFFFFFFF.toInt(), hex("#E6E4F5"), night), lerp(0.76f, 0.66f, night))
        c.drawRoundRect(l, tp, r, b, rad, rad, ui)
        uiLine.color = withAlpha(0xFFFFFFFF.toInt(), 0.6f); uiLine.strokeWidth = 1f * dp
        c.drawRoundRect(l, tp, r, b, rad, rad, uiLine)
    }

    private fun bar(c: Canvas, l: Float, y: Float, w: Float, h: Float, v: Float, col: Int) {
        ui.color = hex("#E7EAF0"); c.drawRoundRect(l, y, l + w, y + h, h / 2f, h / 2f, ui)
        if (v > 0.01f) { ui.color = col; c.drawRoundRect(l, y, l + max(h, w * clamp01(v)), y + h, h / 2f, h / 2f, ui) }
    }

    private fun drawHud(c: Canvas) {
        val night = forest.night
        val now = System.currentTimeMillis()
        if (st.exists) {
            val l = 16f * dp; val r = vw - 16f * dp
            card(c, l, hudTop, r, hudBottom, 22f * dp, night)
            val pad = 18f * dp
            // linha 1
            txtB.color = cText; txtB.textSize = sp(19f); txtB.textAlign = Paint.Align.LEFT
            c.drawText(st.name, l + pad, hudTop + 32f * dp, txtB)
            val nameW = txtB.measureText(st.name)
            val chip = "${st.rarity.label} · Prata"
            txt.textSize = sp(11.5f); txt.textAlign = Paint.Align.LEFT
            val cw = txt.measureText(chip) + 16f * dp
            val cx0 = l + pad + nameW + 10f * dp
            ui.color = hex("#E9E4F5"); c.drawRoundRect(cx0, hudTop + 17f * dp, cx0 + cw, hudTop + 37f * dp, 10f * dp, 10f * dp, ui)
            txt.color = hex("#7A6FA0"); c.drawText(chip, cx0 + 8f * dp, hudTop + 31.5f * dp, txt)
            val sleeping = st.isSleeping(now)
            val mood = st.moodLabel(sleeping)
            txtB.textSize = sp(13.5f); txtB.textAlign = Paint.Align.RIGHT; txtB.color = cSub
            c.drawText(mood, r - pad, hudTop + 31f * dp, txtB)
            val mw = txtB.measureText(mood)
            val moodC = when {
                st.stage == Stage.EGG -> hex("#BFD8F2"); sleeping -> hex("#C9C0EA")
                st.mood() > 64f -> hex("#F2B8C6"); st.mood() > 40f -> hex("#F6DDB0"); else -> hex("#C9CEDD")
            }
            ui.color = moodC; c.drawCircle(r - pad - mw - 10f * dp, hudTop + 26.5f * dp, 5.5f * dp, ui)
            // linha 2
            txt.textSize = sp(13f); txt.color = cText; txt.textAlign = Paint.Align.LEFT
            val stageTxt = if (st.stage == Stage.EGG) "Ovo" else "${st.stage.label} · ${st.ageDays(now)}d de vida"
            c.drawText(stageTxt, l + pad, hudTop + 58f * dp, txt)
            txt.textAlign = Paint.Align.RIGHT; txt.color = cSub
            val nxt = st.stage.next
            val rightBase = when {
                st.stage == Stage.EGG -> "choca em ${fmtDuration(st.remainingMs())}"
                nxt == null -> "fase final"
                else -> "${nxt.label} em ${fmtDuration(st.remainingMs())}"
            }
            val right = if (st.timeScale > 1f) "$rightBase  (${st.timeScale.toInt()}×)" else rightBase
            c.drawText(right, r - pad, hudTop + 58f * dp, txt)
            bar(c, l + pad, hudTop + 65f * dp, r - l - pad * 2f, 6f * dp, st.progress(), hex("#B9CFEA"))
            // linha 3
            if (st.stage == Stage.EGG) {
                txt.textAlign = Paint.Align.LEFT; txt.color = cSub; txt.textSize = sp(12.5f)
                c.drawText("Toque ou esfregue o ovo para aquecê-lo.", l + pad, hudTop + 96f * dp, txt)
            } else {
                val colW = (r - l - pad * 2f) / 3f
                val vals = floatArrayOf(st.satiety, st.happiness, st.energy)
                val cols = intArrayOf(hex("#F3C1A8"), hex("#F2B8C6"), hex("#C8BFE8"))
                for (i in 0..2) {
                    val x0 = l + pad + i * colW
                    val iy = hudTop + 92f * dp
                    drawStatIcon(c, i, x0 + 7f * dp, iy, cols[i])
                    bar(c, x0 + 18f * dp, iy - 3f * dp, colW - 28f * dp, 6f * dp, vals[i] / 100f, cols[i])
                }
            }
            drawDock(c, now, night)
        }
        // aviso
        if (toastT > 0f && toastMsg.isNotEmpty()) {
            val a = min(1f, toastT / 0.4f) * min(1f, (3.2f - toastT) / 0.25f)
            txtB.textSize = sp(14f); txtB.textAlign = Paint.Align.CENTER
            val tw = min(txtB.measureText(toastMsg), vw - 64f * dp)
            val y = hudBottom + 30f * dp
            ui.color = withAlpha(hex("#FFFDF8"), 0.92f * a)
            c.drawRoundRect(vw / 2f - tw / 2f - 16f * dp, y - 22f * dp, vw / 2f + tw / 2f + 16f * dp, y + 12f * dp, 17f * dp, 17f * dp, ui)
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
        val r = 7f * dp
        val dark = lerpColor(col, hex("#6B7285"), 0.35f)
        when (i) {
            0 -> { // maçã
                ui.color = col; c.drawCircle(cx, cy + r * 0.1f, r * 0.85f, ui)
                uiLine.color = dark; uiLine.strokeWidth = 1.6f * dp
                c.drawLine(cx, cy - r * 0.6f, cx + r * 0.2f, cy - r * 1.05f, uiLine)
                ui.color = hex("#AFCDA4"); c.oval(cx + r * 0.5f, cy - r * 0.85f, r * 0.4f, r * 0.2f, ui)
            }
            1 -> { // coração
                c.save(); c.translate(cx, cy); c.scale(r * 2f, r * 2f)
                ui.color = col; c.drawPath(heartPath, ui); c.restore()
            }
            else -> { // lua
                ui.color = col; c.drawCircle(cx, cy, r * 0.85f, ui)
                ui.color = hex("#F7F8FB"); c.drawCircle(cx + r * 0.4f, cy - r * 0.3f, r * 0.7f, ui)
            }
        }
    }

    private fun drawDock(c: Canvas, now: Long, night: Float) {
        val l = 16f * dp; val r = vw - 16f * dp
        card(c, l, dockTop, r, dockBottom, 28f * dp, night)
        val cw = (r - l) / 4f
        val h = Clock.hourOf(now)
        val egg = st.stage == Stage.EGG
        val sleeping = st.isSleeping(now)
        // comer
        val meal = if (egg) null else st.feedableMeal(now)
        val cur = Clock.currentMeal(h)
        val foodLabel = when {
            egg -> "Comer"
            meal != null -> meal.short
            cur != null && st.fedToday(cur, now) -> "Satisfeito"
            else -> { val nx = Clock.nextMeal(h); "${nx.short} ${nx.start}h" }
        }
        val labels = arrayOf(
            foodLabel,
            "Brincar",
            when { Clock.isNight(h) -> "Boa noite"; now < st.napUntil -> "Acordar"; else -> "Soneca" },
            "Diário"
        )
        val enabled = booleanArrayOf(
            meal != null && mode != Mode.SLEEP,
            !egg && !sleeping && st.energy >= 15f,
            !egg && !Clock.isNight(h),
            true
        )
        val bg = intArrayOf(cFood, cPlay, cSleep, cDiary)
        val fg = intArrayOf(cFoodI, cPlayI, cSleepI, cDiaryI)
        for (i in 0..3) {
            val cx = l + cw * (i + 0.5f)
            val cy = dockTop + 37f * dp
            var rad = 25f * dp
            if (i == 0 && meal != null) rad *= 1f + 0.05f * sin(t * 3f)
            if (pressedBtn == i && touching) rad *= 0.92f
            val a = if (enabled[i]) 1f else 0.45f
            ui.color = withAlpha(bg[i], a); c.drawCircle(cx, cy, rad, ui)
            drawButtonIcon(c, i, cx, cy, withAlpha(fg[i], a), withAlpha(bg[i], 1f), a)
            txtB.textSize = sp(12f); txtB.textAlign = Paint.Align.CENTER; txtB.color = withAlpha(cText, 0.4f + 0.6f * a)
            c.drawText(labels[i], cx, dockTop + 78f * dp, txtB)
        }
    }

    private fun drawButtonIcon(c: Canvas, i: Int, cx: Float, cy: Float, col: Int, bgCol: Int, a: Float) {
        val r = 11f * dp
        ui.color = col; uiLine.color = col; uiLine.strokeWidth = 2.2f * dp
        when (i) {
            0 -> { // tigela com vapor
                tmpPath.reset()
                tmpPath.moveTo(cx - r, cy - r * 0.05f); tmpPath.lineTo(cx + r, cy - r * 0.05f)
                tmpPath.quadTo(cx + r * 0.95f, cy + r * 0.85f, cx, cy + r * 0.85f)
                tmpPath.quadTo(cx - r * 0.95f, cy + r * 0.85f, cx - r, cy - r * 0.05f); tmpPath.close()
                c.drawPath(tmpPath, ui)
                c.drawCircle(cx - r * 0.35f, cy - r * 0.2f, r * 0.28f, ui)
                c.drawCircle(cx + r * 0.25f, cy - r * 0.25f, r * 0.32f, ui)
                for (k in -1..1 step 2) {
                    tmpPath.reset(); tmpPath.moveTo(cx + k * r * 0.35f, cy - r * 0.6f)
                    tmpPath.quadTo(cx + k * r * 0.35f + r * 0.25f, cy - r * 0.85f, cx + k * r * 0.35f, cy - r * 1.1f)
                    c.drawPath(tmpPath, uiLine)
                }
            }
            1 -> { // novelo
                c.drawCircle(cx, cy, r * 0.85f, uiLine)
                tmpPath.reset(); tmpPath.moveTo(cx - r * 0.7f, cy - r * 0.4f); tmpPath.quadTo(cx, cy + r * 0.2f, cx + r * 0.7f, cy - r * 0.4f); c.drawPath(tmpPath, uiLine)
                tmpPath.reset(); tmpPath.moveTo(cx - r * 0.75f, cy + r * 0.25f); tmpPath.quadTo(cx, cy + r * 0.8f, cx + r * 0.7f, cy + r * 0.3f); c.drawPath(tmpPath, uiLine)
            }
            2 -> { // lua
                c.drawCircle(cx, cy, r * 0.85f, ui)
                ui.color = bgCol; c.drawCircle(cx + r * 0.42f, cy - r * 0.32f, r * 0.72f, ui)
                ui.color = col
                c.drawCircle(cx + r * 0.75f, cy + r * 0.55f, r * 0.12f, ui)
            }
            else -> { // livro
                rect.set(cx - r * 0.85f, cy - r * 0.75f, cx + r * 0.85f, cy + r * 0.75f)
                c.drawRoundRect(rect, r * 0.2f, r * 0.2f, uiLine)
                c.drawLine(cx, cy - r * 0.7f, cx, cy + r * 0.7f, uiLine)
                uiLine.strokeWidth = 1.4f * dp
                c.drawLine(cx - r * 0.6f, cy - r * 0.3f, cx - r * 0.2f, cy - r * 0.3f, uiLine)
                c.drawLine(cx - r * 0.6f, cy + r * 0.05f, cx - r * 0.2f, cy + r * 0.05f, uiLine)
            }
        }
    }

    private fun drawDiary(c: Canvas, now: Long) {
        diaryRects.clear()
        ui.color = withAlpha(hex("#2B3040"), 0.3f); c.drawRect(0f, 0f, vw, vh, ui)
        val l = 20f * dp; val r = vw - 20f * dp
        val top = hudTop + 4f * dp
        val bottom = dockBottom
        ui.color = hex("#FBFAF6"); c.drawRoundRect(l, top, r, bottom, 26f * dp, 26f * dp, ui)
        diaryRects.add(Pair(RectF(l, top, r, bottom), -1))
        val pad = 22f * dp
        var y = top + 40f * dp
        txtB.textAlign = Paint.Align.LEFT; txtB.color = cText; txtB.textSize = sp(20f)
        c.drawText("Diário de ${st.name}", l + pad, y, txtB)
        // fechar
        val xC = r - pad - 6f * dp; val yC = y - 7f * dp
        uiLine.color = cSub; uiLine.strokeWidth = 2.2f * dp
        c.drawLine(xC - 7f * dp, yC - 7f * dp, xC + 7f * dp, yC + 7f * dp, uiLine)
        c.drawLine(xC - 7f * dp, yC + 7f * dp, xC + 7f * dp, yC - 7f * dp, uiLine)
        diaryRects.add(0, Pair(RectF(xC - 24f * dp, yC - 24f * dp, xC + 24f * dp, yC + 24f * dp), 0))

        txt.textAlign = Paint.Align.LEFT; txt.textSize = sp(13.5f); txt.color = cText
        y += 30f * dp
        val sleeping = st.isSleeping(now)
        c.drawText("Raridade: ${st.rarity.label} — cresce ${st.rarity.mult.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}× mais devagar", l + pad, y, txt); y += 22f * dp
        c.drawText("Cor: prata com olhos azuis", l + pad, y, txt); y += 22f * dp
        c.drawText("Humor: ${st.moodLabel(sleeping)} · ${st.ageDays(now)} dia(s) de vida", l + pad, y, txt); y += 22f * dp
        c.drawText("Saciedade ${st.satiety.toInt()}% · Alegria ${st.happiness.toInt()}% · Energia ${st.energy.toInt()}%", l + pad, y, txt)

        // linha do tempo
        y += 34f * dp
        val stages = Stage.entries
        val span = (r - l - pad * 2f) / (stages.size - 1)
        uiLine.color = hex("#DCE3EE"); uiLine.strokeWidth = 3f * dp
        c.drawLine(l + pad, y, r - pad, y, uiLine)
        for ((i, s) in stages.withIndex()) {
            val cx = l + pad + span * i
            val done = s.ordinal <= st.stage.ordinal
            ui.color = if (s == st.stage) hex("#9DC2EA") else if (done) hex("#C6D8EE") else hex("#ECEFF4")
            c.drawCircle(cx, y, (if (s == st.stage) 9f else 6.5f) * dp, ui)
            txt.textAlign = Paint.Align.CENTER; txt.textSize = sp(10.5f); txt.color = if (done) cText else cSub
            val short = if (s == Stage.TEEN) "Adolesc." else s.label
            c.drawText(short, cx, y + 24f * dp, txt)
            txt.color = cSub; txt.textSize = sp(9.5f)
            val dur = if (s == Stage.ELDER) "para sempre" else fmtDuration(st.stageDurationMs(s).toLong())
            c.drawText(dur, cx, y + 37f * dp, txt)
        }

        // refeições
        y += 66f * dp
        txtB.textSize = sp(14.5f); txtB.textAlign = Paint.Align.LEFT; txtB.color = cText
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
            txt.textAlign = Paint.Align.RIGHT; txt.color = if (status == "agora!") hex("#C98B6E") else cSub
            c.drawText(status, r - pad, y, txt); txt.textAlign = Paint.Align.LEFT
        }

        // cuidados
        y += 34f * dp
        c.drawText("Como cuidar", l + pad, y, txtB)
        txt.color = cSub; txt.textSize = sp(12.5f)
        val tips = arrayOf(
            "Toque: carinho · Dois toques: pulinho",
            "Segurar: abraço · Esfregar: cafuné",
            "Toque no chão e ele vai até lá",
            "Muitas cutucadas seguidas deixam ele bravinho"
        )
        for (tip in tips) { y += 20f * dp; c.drawText(tip, l + pad, y, txt) }

        // modo teste
        y += 34f * dp
        c.drawText("Modo teste", l + pad, y, txtB)
        y += 12f * dp
        val bw = (r - l - pad * 2f - 10f * dp) / 2f
        val bh = 40f * dp
        val opts = arrayOf(
            "Tempo: ${st.timeScale.toInt()}×" to 1,
            "Refeição livre: ${if (st.freeMeals) "sim" else "não"}" to 2,
            "Avançar fase" to 3,
            "Novo ovo" to 4
        )
        for ((i, o) in opts.withIndex()) {
            val bx = l + pad + (i % 2) * (bw + 10f * dp)
            val by = y + (i / 2) * (bh + 10f * dp)
            if (by + bh > bottom - 10f * dp) break
            val rr = RectF(bx, by, bx + bw, by + bh)
            ui.color = if (o.second == 4) hex("#F6E1DA") else hex("#E8EEF6")
            c.drawRoundRect(rr, bh / 2f, bh / 2f, ui)
            txtB.textSize = sp(13f); txtB.textAlign = Paint.Align.CENTER; txtB.color = cText
            c.drawText(o.first, rr.centerX(), rr.centerY() + 4.5f * dp, txtB)
            diaryRects.add(0, Pair(rr, o.second))
        }
        txt.textAlign = Paint.Align.CENTER; txt.textSize = sp(10.5f); txt.color = cSub
        c.drawText("Ninho · versão de teste · by CoffeeLabs", vw / 2f, bottom - 14f * dp, txt)
    }
}
