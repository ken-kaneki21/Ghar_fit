package dev.ghar.watch.workout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Store
import dev.ghar.watch.presentation.TStep
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Program engine (handover §10). Built-in programs for Saurabh (Bangalore, Nov 2026): gym 6 days rotating A→B→C,
 * treadmill Zone 2 after weights, Just Dance daily at home, sports as quick-start workouts.
 * Muscle keys (~20 groups, used by the body map): chest, front_delts, side_delts, rear_delts, biceps, triceps, forearms,
 * lats, upper_back, traps, lower_back, abs, obliques, glutes, quads, hamstrings, adductors, calves, hip_flexors.
 */
data class Ex(
    val name: String, val sets: Int, val repLo: Int, val repHi: Int, val restS: Int,
    val primary: List<String>, val secondary: List<String> = emptyList(),
    val weighted: Boolean = true, val holdS: Int = 0, val perSide: Boolean = false, val tip: String = "",
)

data class Seg(val name: String, val min: Int, val met: Double, val focus: List<String>, val zone2: Boolean = false)

data class Program(
    val id: String, val name: String, val short: String, val kind: String,   // strength | cardio | dance
    val exerciseType: String,
    val warmup: List<TStep> = emptyList(), val exercises: List<Ex> = emptyList(),
    val segments: List<Seg> = emptyList(), val stretch: List<TStep> = emptyList(),
)

object Programs {
    private val WARM_UPPER = listOf(TStep("March / light jog", 60), TStep("Arm circles", 30), TStep("Shoulder rolls", 30),
        TStep("Wall push-ups", 30), TStep("Torso twists", 30))
    private val WARM_LOWER = listOf(TStep("March / light jog", 60), TStep("Hip circles", 30), TStep("Bodyweight squats", 45),
        TStep("Leg swings · left", 20), TStep("Leg swings · right", 20), TStep("Glute bridges", 30))
    private val WARM_FULL = listOf(TStep("March / light jog", 60), TStep("Arm circles", 30), TStep("Hip circles", 30),
        TStep("Bodyweight squats", 45), TStep("Inchworms", 45))
    private val STRETCH_UPPER = listOf(TStep("Doorway chest stretch", 30), TStep("Lat stretch · left", 30), TStep("Lat stretch · right", 30),
        TStep("Triceps stretch · left", 25), TStep("Triceps stretch · right", 25), TStep("Cross-body shoulder · left", 25),
        TStep("Cross-body shoulder · right", 25), TStep("Child's pose", 40))
    private val STRETCH_LOWER = listOf(TStep("Quad stretch · left", 30), TStep("Quad stretch · right", 30),
        TStep("Hamstring · left", 30), TStep("Hamstring · right", 30), TStep("Hip flexor · left", 30), TStep("Hip flexor · right", 30),
        TStep("Figure-4 glute · left", 30), TStep("Figure-4 glute · right", 30), TStep("Calf · left", 25), TStep("Calf · right", 25))
    private val STRETCH_FULL = listOf(TStep("Doorway chest stretch", 30), TStep("Lat stretch · left", 25), TStep("Lat stretch · right", 25),
        TStep("Quad stretch · left", 25), TStep("Quad stretch · right", 25), TStep("Hamstring · left", 25), TStep("Hamstring · right", 25),
        TStep("Child's pose", 40))
    private val STRETCH_CARDIO = listOf(TStep("Calf · left", 25), TStep("Calf · right", 25), TStep("Quad stretch · left", 25),
        TStep("Quad stretch · right", 25), TStep("Hip flexor · left", 25), TStep("Hip flexor · right", 25))

    private fun treadmill(min: Int) = Seg("Treadmill incline 8–10% · 5–5.5 km/h", min, 5.5, listOf("legs", "glutes"), zone2 = true)

    val GYM_A = Program("gym-a", "Gym A · Upper + treadmill", "A", "strength", "STRENGTH_TRAINING", WARM_UPPER, listOf(
        Ex("Chest press (machine/DB)", 3, 10, 12, 75, listOf("chest"), listOf("triceps", "front_delts")),
        Ex("Lat pulldown", 3, 10, 12, 75, listOf("lats"), listOf("biceps", "rear_delts")),
        Ex("Seated cable row", 3, 10, 12, 75, listOf("upper_back"), listOf("biceps", "rear_delts")),
        Ex("DB shoulder press", 3, 10, 12, 75, listOf("front_delts", "side_delts"), listOf("triceps")),
        Ex("Biceps curl", 3, 10, 12, 60, listOf("biceps"), listOf("forearms")),
        Ex("Triceps pushdown", 3, 10, 12, 60, listOf("triceps")),
        Ex("Plank", 3, 0, 0, 45, listOf("abs"), listOf("obliques"), weighted = false, holdS = 40),
    ), listOf(treadmill(20)), STRETCH_UPPER)

    val GYM_B = Program("gym-b", "Gym B · Lower + core + treadmill", "B", "strength", "STRENGTH_TRAINING", WARM_LOWER, listOf(
        Ex("Goblet squat / leg press", 3, 10, 12, 90, listOf("quads", "glutes"), listOf("adductors")),
        Ex("DB Romanian deadlift", 3, 10, 12, 90, listOf("hamstrings", "glutes"), listOf("lower_back"), tip = "Hips back, flat back"),
        Ex("Walking lunges", 3, 10, 10, 75, listOf("quads", "glutes"), listOf("hamstrings"), perSide = true),
        Ex("Glute bridge", 3, 12, 15, 60, listOf("glutes"), listOf("hamstrings")),
        Ex("Calf raises", 3, 12, 15, 45, listOf("calves")),
        Ex("Bird-dog (hold 8 s)", 2, 6, 6, 30, listOf("lower_back", "glutes"), listOf("abs"), weighted = false, perSide = true),
        Ex("Dead bug", 2, 8, 8, 30, listOf("abs"), weighted = false, perSide = true),
        Ex("Side plank", 2, 0, 0, 30, listOf("obliques"), listOf("abs"), weighted = false, holdS = 25, perSide = true),
    ), listOf(treadmill(20)), STRETCH_LOWER)

    val GYM_C = Program("gym-c", "Gym C · Full body (light) + treadmill", "C", "strength", "STRENGTH_TRAINING", WARM_FULL, listOf(
        Ex("Goblet squat (light)", 3, 10, 12, 75, listOf("quads", "glutes")),
        Ex("Push-ups", 3, 8, 12, 60, listOf("chest"), listOf("triceps", "front_delts"), weighted = false),
        Ex("Lat pulldown", 3, 10, 12, 75, listOf("lats"), listOf("biceps")),
        Ex("Glute bridge", 3, 12, 15, 60, listOf("glutes"), listOf("hamstrings")),
        Ex("Farmer walk", 3, 0, 0, 60, listOf("forearms", "traps"), listOf("abs"), holdS = 40),
        Ex("Dead bug", 3, 8, 8, 45, listOf("abs"), weighted = false, perSide = true),
    ), listOf(treadmill(25)), STRETCH_FULL)

    val HOME = Program("home-full", "Home · Full body (DB + push-up bars)", "H", "strength", "STRENGTH_TRAINING", WARM_FULL, listOf(
        Ex("DB goblet squat (5 kg)", 3, 12, 15, 60, listOf("quads", "glutes")),
        Ex("Push-ups on bars", 3, 8, 12, 60, listOf("chest"), listOf("triceps", "front_delts"), weighted = false),
        Ex("One-arm DB row", 3, 10, 12, 60, listOf("lats", "upper_back"), listOf("biceps"), perSide = true),
        Ex("DB shoulder press", 3, 10, 12, 60, listOf("front_delts", "side_delts"), listOf("triceps")),
        Ex("DB Romanian deadlift", 3, 12, 15, 60, listOf("hamstrings", "glutes"), listOf("lower_back")),
        Ex("Ankle-weight glute kickback", 2, 12, 15, 45, listOf("glutes"), listOf("hamstrings"), perSide = true),
        Ex("Plank", 3, 0, 0, 45, listOf("abs"), listOf("obliques"), weighted = false, holdS = 40),
    ), emptyList(), STRETCH_FULL)

    val TREADMILL = Program("treadmill-z2", "Treadmill · Zone 2 · 31 min", "T", "cardio", "WALKING", emptyList(), emptyList(), listOf(
        Seg("Warm-up walk · flat · 4.5 km/h", 3, 3.5, listOf("legs")),
        treadmill(25),
        Seg("Cool-down · flat · 4 km/h", 3, 3.0, listOf("legs")),
    ), STRETCH_CARDIO)

    private fun jd(min: Int) = Program("jd-$min", "Just Dance · $min min", "J", "dance", "DANCING", emptyList(), emptyList(), listOf(
        Seg("Warm-up song", 4, 4.0, listOf("legs", "shoulders")),
        Seg("Cardio songs", min - 7, 6.0, listOf("legs", "glutes", "core", "arms", "shoulders")),
        Seg("Cool-down song", 3, 3.0, listOf("full body")),
    ), STRETCH_CARDIO)
    val JD20 = jd(20)
    val JD30 = jd(30)

    val ALL = listOf(GYM_A, GYM_B, GYM_C, HOME, TREADMILL, JD20, JD30)
    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /** Gym rotation A → B → C → A, advanced when a gym session is finished. */
    fun nextGym(): Program = byId(Store.kvGet("gym_next") ?: "gym-a") ?: GYM_A
    fun advanceGym(done: Program) {
        val order = listOf("gym-a", "gym-b", "gym-c")
        val i = order.indexOf(done.id); if (i >= 0) Store.kvSet("gym_next", order[(i + 1) % 3])
    }

    /** Coarse focus words → muscle keys (for cardio / dance estimates). */
    val FOCUS = mapOf(
        "legs" to listOf("quads", "hamstrings", "calves"), "glutes" to listOf("glutes"), "core" to listOf("abs", "obliques"),
        "arms" to listOf("biceps", "triceps"), "shoulders" to listOf("front_delts", "side_delts"),
        "full body" to listOf("quads", "glutes", "abs", "front_delts"),
    )
}

/** One running program session (lives in memory; if the app is killed, the workout itself continues and recovers). */
object Session {
    data class SetLog(val ex: String, val set: Int, val reps: Int, val kg: Double, val holdS: Int)

    var program by mutableStateOf<Program?>(null)
    var phase by mutableStateOf("warmup")          // warmup | strength | cardio | stretch
    var exIdx by mutableIntStateOf(0)
    var setIdx by mutableIntStateOf(1)
    var resting by mutableStateOf(false)
    val logs = mutableListOf<SetLog>()
    val segDone = mutableListOf<Seg>()

    fun start(p: Program) {
        program = p; logs.clear(); segDone.clear(); exIdx = 0; setIdx = 1; resting = false
        phase = firstPhase(p)
    }
    private fun firstPhase(p: Program) = when {
        p.warmup.isNotEmpty() -> "warmup"; p.exercises.isNotEmpty() -> "strength"; p.segments.isNotEmpty() -> "cardio"; else -> "stretch"
    }
    fun nextPhase() {
        val p = program ?: return
        phase = when (phase) {
            "warmup" -> if (p.exercises.isNotEmpty()) "strength" else if (p.segments.isNotEmpty()) "cardio" else "stretch"
            "strength" -> if (p.segments.isNotEmpty()) "cardio" else "stretch"
            "cardio" -> "stretch"
            else -> "done"
        }
    }

    fun ex(): Ex? = program?.exercises?.getOrNull(exIdx)

    // ---- per-exercise memory for defaults + progressive overload ----
    private fun key(e: Ex) = "ex:" + e.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
    fun lastKg(e: Ex): Double = Store.kvGet(key(e) + ":kg")?.toDoubleOrNull() ?: 0.0
    /** True when every set last time reached the top of the rep range → add weight today. */
    fun shouldIncrease(e: Ex): Boolean = e.weighted && e.holdS == 0 && Store.kvGet(key(e) + ":top") == "1"

    fun logSet(e: Ex, reps: Int, kg: Double) {
        logs += SetLog(e.name, setIdx, reps, kg, if (e.holdS > 0) reps else 0)
        if (setIdx >= e.sets) {
            val mine = logs.filter { it.ex == e.name }
            if (kg > 0) Store.kvSet(key(e) + ":kg", kg.toString())
            Store.kvSet(key(e) + ":top", if (e.holdS == 0 && mine.size >= e.sets && mine.all { it.reps >= e.repHi }) "1" else "0")
            resting = false
            if (exIdx + 1 < (program?.exercises?.size ?: 0)) { exIdx += 1; setIdx = 1; resting = true } else nextPhase()
        } else { setIdx += 1; resting = true }
    }
    fun skipExercise() {
        resting = false
        if (exIdx + 1 < (program?.exercises?.size ?: 0)) { exIdx += 1; setIdx = 1 } else nextPhase()
    }

    /** Sets + body-part split (handover §10): strength = primary 1.0 / secondary 0.5 per set; cardio = min × MET / 30 per focus muscle. */
    fun buildExtra(): JSONObject {
        val p = program
        val muscles = HashMap<String, Double>()
        fun add(m: String, v: Double) { muscles[m] = (muscles[m] ?: 0.0) + v }
        val sets = JSONArray()
        for (l in logs) {
            sets.put(JSONObject().put("ex", l.ex).put("set", l.set).put("reps", l.reps).put("kg", l.kg).put("hold_s", l.holdS))
            val e = p?.exercises?.firstOrNull { it.name == l.ex } ?: continue
            e.primary.forEach { add(it, 1.0) }; e.secondary.forEach { add(it, 0.5) }
        }
        var metKcal = 0.0
        for (s in segDone) {
            s.focus.flatMap { Programs.FOCUS[it] ?: emptyList() }.distinct().forEach { add(it, s.min * s.met / 30.0) }
            metKcal += s.met * dev.ghar.watch.data.Prefs.weightKg * s.min / 60.0
        }
        val split = JSONObject().put("unit", "set_eq").put("muscles",
            JSONObject().apply { muscles.forEach { (k, v) -> put(k, (v * 10).roundToInt() / 10.0) } })
        return JSONObject().put("sets", sets).put("body_split", split)
            .put("program", p?.id ?: "").put("kcal_met", metKcal.roundToInt())
    }

    fun clear() { program = null; logs.clear(); segDone.clear(); phase = "warmup" }

    /** Zone 2 band for this person: 60–70 % of estimated max HR. */
    fun zone2(): IntRange { val m = Day.hrMaxEst(); return (m * 0.6).roundToInt()..(m * 0.7).roundToInt() }
}
