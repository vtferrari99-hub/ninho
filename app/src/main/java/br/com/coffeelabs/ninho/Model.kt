package br.com.coffeelabs.ninho

import android.content.Context
import java.util.Calendar

enum class Stage(val label: String, val baseHours: Double) {
    EGG("Ovo", 0.5),
    BABY("Bebê", 24.0),
    CHILD("Criança", 48.0),
    TEEN("Adolescente", 72.0),
    ADULT("Adulto", 168.0),
    ELDER("Ancião", 0.0);

    val next: Stage? get() = if (ordinal < entries.size - 1) entries[ordinal + 1] else null
}

/** Quanto mais rara, mais devagar cresce. */
enum class Rarity(val label: String, val mult: Double) {
    COMUM("Comum", 1.0),
    INCOMUM("Incomum", 1.5),
    RARO("Raro", 2.0),
    EPICO("Épico", 3.0),
    LENDARIO("Lendário", 4.0)
}

enum class Meal(val label: String, val short: String, val start: Int, val end: Int, val food: String) {
    BREAKFAST("Café da manhã", "Café", 6, 10, "frutinhas silvestres"),
    LUNCH("Almoço", "Almoço", 11, 14, "peixinho do riacho"),
    DINNER("Jantar", "Jantar", 18, 21, "maçã assada");
}

object Clock {
    private val cal: Calendar = Calendar.getInstance()

    @Synchronized
    fun hourOf(ms: Long): Float {
        cal.timeInMillis = ms
        return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f + cal.get(Calendar.SECOND) / 3600f
    }

    @Synchronized
    fun dayOf(ms: Long): Int {
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }

    fun isNight(h: Float): Boolean = h >= 22f || h < 6f

    fun currentMeal(h: Float): Meal? = Meal.entries.firstOrNull { h >= it.start && h < it.end }

    fun nextMeal(h: Float): Meal = Meal.entries.firstOrNull { h < it.start } ?: Meal.BREAKFAST
}

class DragonState {
    var exists = false
    var name = "Lumi"
    var rarity = Rarity.RARO
    var colorKey = "prata"
    var stage = Stage.EGG
    var growthMs = 0.0
    var satiety = 70f
    var happiness = 75f
    var energy = 85f
    var createdMs = 0L
    var lastSimMs = 0L
    val lastFed = IntArray(3)
    val penalized = IntArray(3)
    var napUntil = 0L
    var timeScale = 1f
    var freeMeals = false

    // eventos transitórios para a tela
    var evolvedTo: Stage? = null
    val missed = ArrayList<Meal>()

    fun stageDurationMs(s: Stage = stage): Double = s.baseHours * rarity.mult * 3_600_000.0

    fun progress(): Float =
        if (stage == Stage.ELDER) 1f else (growthMs / stageDurationMs()).toFloat().coerceIn(0f, 1f)

    fun remainingMs(): Long {
        if (stage == Stage.ELDER) return 0L
        return ((stageDurationMs() - growthMs) / timeScale.coerceAtLeast(1f)).toLong().coerceAtLeast(0L)
    }

    fun isSleeping(now: Long): Boolean =
        exists && stage != Stage.EGG && (Clock.isNight(Clock.hourOf(now)) || now < napUntil)

    fun mood(): Float = happiness * 0.55f + satiety * 0.25f + energy * 0.2f

    fun moodLabel(sleeping: Boolean): String {
        if (stage == Stage.EGG) return "Quentinho"
        if (sleeping) return "Dormindo"
        val m = mood()
        return when {
            energy < 18f -> "Sonolento"
            satiety < 18f -> "Com fome"
            m > 80f -> "Radiante"
            m > 64f -> "Feliz"
            m > 46f -> "Tranquilo"
            m > 30f -> "Entediado"
            else -> "Tristinho"
        }
    }

    fun newEgg(nm: String, now: Long) {
        exists = true
        name = nm
        rarity = Rarity.RARO
        colorKey = "prata"
        stage = Stage.EGG
        growthMs = 0.0
        satiety = 70f; happiness = 75f; energy = 85f
        createdMs = now
        lastSimMs = now
        for (i in 0..2) { lastFed[i] = 0; penalized[i] = 0 }
        napUntil = 0L
        evolvedTo = null
        missed.clear()
    }

    fun simulateTo(now: Long) {
        if (!exists) return
        if (lastSimMs == 0L || now < lastSimMs) { lastSimMs = now; return }
        var t = lastSimMs
        val step = 5 * 60_000L
        // limita a 30 dias de simulação fora do app
        if (now - t > 30L * 24 * 3_600_000L) t = now - 30L * 24 * 3_600_000L
        while (now > t) {
            val dt = minOf(step, now - t)
            t += dt
            stepOnce(dt, t)
        }
        lastSimMs = now
    }

    private fun stepOnce(dtMs: Long, at: Long) {
        if (stage == Stage.EGG) {
            addGrowth(dtMs.toDouble(), at)
            return
        }
        val h = Clock.hourOf(at)
        val hours = dtMs / 3_600_000f
        val sleeping = Clock.isNight(h) || at < napUntil
        satiety -= hours * (if (sleeping) 2.5f else 6f)
        energy += hours * (if (sleeping) 14f else -4f)
        var hd = 2.5f
        if (satiety < 20f) hd += 3f
        if (energy < 15f) hd += 2f
        if (sleeping) hd *= 0.4f
        happiness -= hours * hd
        clampStats()

        val day = Clock.dayOf(at)
        for (m in Meal.entries) {
            val i = m.ordinal
            if (h >= m.end && lastFed[i] != day && penalized[i] != day) {
                penalized[i] = day
                happiness = (happiness - 8f).coerceAtLeast(0f)
                if (!missed.contains(m)) missed.add(m)
            }
        }
        val rate = if (satiety > 25f && happiness > 25f) 1.0 else 0.5
        addGrowth(dtMs * rate, at)
    }

    private fun addGrowth(ms: Double, at: Long) {
        if (stage == Stage.ELDER) return
        growthMs += ms * timeScale
        while (stage != Stage.ELDER && growthMs >= stageDurationMs()) {
            growthMs -= stageDurationMs()
            advance(at)
        }
        if (stage == Stage.ELDER) growthMs = 0.0
    }

    private fun advance(at: Long) {
        val old = stage
        stage = stage.next ?: Stage.ELDER
        evolvedTo = stage
        if (old == Stage.EGG) {
            satiety = 60f; happiness = 85f; energy = 80f
            // não pune refeições que já passaram no dia em que nasceu
            val h = Clock.hourOf(at)
            val day = Clock.dayOf(at)
            for (m in Meal.entries) if (h >= m.end) penalized[m.ordinal] = day
        }
    }

    fun forceAdvance(now: Long) {
        if (stage == Stage.ELDER) return
        growthMs = 0.0
        advance(now)
    }

    fun clampStats() {
        satiety = satiety.coerceIn(0f, 100f)
        happiness = happiness.coerceIn(0f, 100f)
        energy = energy.coerceIn(0f, 100f)
    }

    /** Refeição disponível agora (ou null). */
    fun feedableMeal(now: Long): Meal? {
        if (stage == Stage.EGG) return null
        val h = Clock.hourOf(now)
        val day = Clock.dayOf(now)
        val cur = Clock.currentMeal(h)
        if (freeMeals) return cur ?: Clock.nextMeal(h)
        if (cur != null && lastFed[cur.ordinal] != day) return cur
        return null
    }

    fun fedToday(m: Meal, now: Long): Boolean = lastFed[m.ordinal] == Clock.dayOf(now)

    fun feed(m: Meal, now: Long) {
        satiety += 45f
        happiness += 10f
        lastFed[m.ordinal] = Clock.dayOf(now)
        clampStats()
    }

    fun ageDays(now: Long): Int = ((now - createdMs) / (24L * 3_600_000L)).toInt()

    // ------------------------------------------------------------ persistência
    fun save(ctx: Context) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        e.putBoolean("exists", exists)
        e.putString("name", name)
        e.putString("rarity", rarity.name)
        e.putString("color", colorKey)
        e.putString("stage", stage.name)
        e.putLong("growth", growthMs.toLong())
        e.putFloat("satiety", satiety)
        e.putFloat("happiness", happiness)
        e.putFloat("energy", energy)
        e.putLong("created", createdMs)
        e.putLong("lastSim", lastSimMs)
        for (i in 0..2) {
            e.putInt("fed$i", lastFed[i])
            e.putInt("pen$i", penalized[i])
        }
        e.putLong("nap", napUntil)
        e.putFloat("timeScale", timeScale)
        e.putBoolean("freeMeals", freeMeals)
        e.apply()
    }

    companion object {
        const val PREFS = "ninho"

        fun load(ctx: Context): DragonState {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val s = DragonState()
            s.exists = p.getBoolean("exists", false)
            s.name = p.getString("name", "Lumi") ?: "Lumi"
            s.rarity = runCatching { Rarity.valueOf(p.getString("rarity", "RARO")!!) }.getOrDefault(Rarity.RARO)
            s.colorKey = p.getString("color", "prata") ?: "prata"
            s.stage = runCatching { Stage.valueOf(p.getString("stage", "EGG")!!) }.getOrDefault(Stage.EGG)
            s.growthMs = p.getLong("growth", 0L).toDouble()
            s.satiety = p.getFloat("satiety", 70f)
            s.happiness = p.getFloat("happiness", 75f)
            s.energy = p.getFloat("energy", 85f)
            s.createdMs = p.getLong("created", 0L)
            s.lastSimMs = p.getLong("lastSim", 0L)
            for (i in 0..2) {
                s.lastFed[i] = p.getInt("fed$i", 0)
                s.penalized[i] = p.getInt("pen$i", 0)
            }
            s.napUntil = p.getLong("nap", 0L)
            s.timeScale = p.getFloat("timeScale", 1f)
            s.freeMeals = p.getBoolean("freeMeals", false)
            return s
        }
    }
}
