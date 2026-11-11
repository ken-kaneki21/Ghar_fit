package dev.ghar.watch.presentation

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import dev.ghar.watch.data.Day
import dev.ghar.watch.presentation.ui.Bars
import dev.ghar.watch.presentation.ui.Hero
import dev.ghar.watch.presentation.ui.HrLine
import dev.ghar.watch.presentation.ui.StatLine
import dev.ghar.watch.presentation.ui.Stepper
import dev.ghar.watch.presentation.ui.Tile
import dev.ghar.watch.system.WaterNotify
import kotlin.math.log10
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import dev.ghar.watch.health.Hrv
import dev.ghar.watch.health.Recovery
import dev.ghar.watch.presentation.ui.DayBars
import dev.ghar.watch.presentation.ui.MiniLine
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import dev.ghar.watch.presentation.ui.ZoneBar
import dev.ghar.watch.presentation.ui.ZoneColors
import dev.ghar.watch.workout.Workout
import dev.ghar.watch.workout.Programs
import dev.ghar.watch.workout.Session
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import dev.ghar.watch.health.Passive
import dev.ghar.watch.presentation.ui.Caption
import dev.ghar.watch.presentation.ui.G
import dev.ghar.watch.presentation.ui.GScreen
import dev.ghar.watch.presentation.ui.GType
import dev.ghar.watch.presentation.ui.GharTheme
import dev.ghar.watch.presentation.ui.Pill
import dev.ghar.watch.presentation.ui.Rings
import dev.ghar.watch.presentation.ui.RoundButtons
import dev.ghar.watch.sync.Battery
import dev.ghar.watch.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Workout.recoverIfNeeded(this)
        val route = intent?.getStringExtra("route")
        setContent { GharTheme { App(route) } }
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Rec.zone).toLocalTime().format(HM)
private fun fmt(n: Long) = "%,d".format(n)

/** Everything the screens show. Read off the main thread; the UI redraws only when a value changes. */
private data class Snap(
    val steps: Long = 0, val move: Long = 0, val kcalTotal: Long = 0, val floors: Long = 0, val km: Double = 0.0,
    val exMin: Int = 0, val hours: Int = 0, val water: Int = 0,
    val hStepsList: List<Double> = emptyList(), val hKcal: List<Double> = emptyList(), val hEx: List<Double> = emptyList(),
    val hHr: List<Double> = emptyList(), val hrLine: List<Int> = emptyList(), val nowSlot: Int = 0, val hrRange: String = "", val nowHour: Int = 0,
    val hr: String = "–", val hrTime: String = "", val sleep: String = "", val sleepDetail: String = "",
    val pending: Int = 0, val dead: Int = 0, val lastUpload: String = "never", val error: String = "",
    val passive: String = "", val battery: String = "", val configured: Boolean = false,
    val permsOk: Boolean = true, val bgOk: Boolean = true, val notifOk: Boolean = true, val checkinDue: Boolean = false,
)

private fun readSnap(ctx: Context): Snap {
    Store.init(ctx)
    val d = Rec.today()
    val raw = Rec.dailyValue(d, "kcal_total") ?: 0.0
    val (active, total) = Rec.kcal(d, raw)
    val now = LocalTime.now()
    val hrRaw = Store.kvGet("last_hr")?.split("@")
    val open = Store.kvGet("sleep_start")?.toLongOrNull()
    val last = Store.kvGet("last_sleep")?.split("-")?.mapNotNull { it.toLongOrNull() }
    val (sleep, sleepDetail) = when {
        open != null -> "Asleep" to "since ${hm(open)}"
        last != null && last.size == 2 -> "%.1f".format((last[1] - last[0]) / 3_600_000.0) to "${hm(last[0])} – ${hm(last[1])}"
        else -> "–" to "Nothing recorded yet"
    }
    val b = Battery.read(ctx)
    val range = Day.hrRange(d)
    return Snap(
        steps = (Rec.dailyValue(d, "steps") ?: 0.0).toLong(),
        move = active.toLong(), kcalTotal = total.toLong(),
        floors = (Rec.dailyValue(d, "floors") ?: 0.0).toLong(),
        km = (Rec.dailyValue(d, "distance_m") ?: 0.0) / 1000.0,
        exMin = Day.exerciseMinutes(d), hours = Day.activeHours(d), water = Day.waterMl(d),
        hStepsList = Day.hourly(d, "steps").toList(), hKcal = Day.hourly(d, "kcal").toList(),
        hEx = Day.hourly(d, "exmin").toList(), hHr = Day.hourlyHrAvg(d).toList(),
        hrLine = Day.hrLine5(d), nowSlot = now.toSecondOfDay() / 300,
        hrRange = range?.let { "${it.first}–${it.third} bpm · avg ${it.second}" } ?: "",
        nowHour = now.hour,
        hr = hrRaw?.getOrNull(0) ?: "–",
        hrTime = hrRaw?.getOrNull(1)?.toLongOrNull()?.let { hm(it) } ?: "",
        sleep = sleep, sleepDetail = sleepDetail,
        pending = Store.pending(), dead = Store.deadCount(),
        lastUpload = Store.kvGet("last_upload")?.toLongOrNull()?.let { hm(it) } ?: "never",
        error = Store.kvGet("last_error") ?: "",
        passive = Store.kvGet("passive_status") ?: "tracking not started",
        battery = b?.let { "${it.pct}%" + if (it.charging) " · charging" else "" } ?: "",
        configured = Prefs.configured,
        permsOk = Passive.has(ctx, Passive.ACT_PERM) && Passive.has(ctx, Passive.HR_PERM),
        bgOk = Passive.has(ctx, Passive.BG_PERM),
        notifOk = WaterNotify.allowed(ctx),
        checkinDue = Store.kvGet("checkin_done") != d.toString() && now.hour in 5..11,
    )
}

@Composable
private fun rememberSnap(refreshKey: Int): Snap {
    val ctx = LocalContext.current
    val s by produceState(initialValue = Snap(), refreshKey) {
        while (true) {
            val n = withContext(Dispatchers.IO) { readSnap(ctx) }
            if (n != value) value = n            // no redraw when nothing changed
            delay(5_000)
        }
    }
    return s
}

@Composable
private fun App(startRoute: String? = null) {
    val nav = rememberSwipeDismissableNavController()
    LaunchedEffect(Unit) { if (startRoute in setOf("workout", "stretch", "interval", "mobility", "timers")) nav.navigate(startRoute!!) }
    var refresh by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { refresh++ }
    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(refresh, go = { nav.navigate(it) }, bump = bump) }
        composable("move") { MoveScreen(refresh) }
        composable("exercise") { ExerciseScreen(refresh) }
        composable("hours") { HoursScreen(refresh) }
        composable("heart") { HeartScreen(refresh) }
        composable("water") { WaterScreen(refresh, bump) }
        composable("body") { BodyScreen { bump(); nav.popBackStack() } }
        composable("checkin") { CheckinScreen { bump(); nav.popBackStack() } }
        composable("status") { StatusScreen(refresh, bump) }
        composable("settings") { SettingsScreen(bump) }
        composable("hrv") { HrvScreen(bump) }
        composable("timers") { TimersScreen { nav.navigate(it) } }
        composable("interval") { IntervalScreen { bump(); nav.popBackStack() } }
        composable("stretch") { RoutineRunner("Desk stretch", Routines.DESK, G.Sleep, null) { nav.popBackStack() } }
        composable("mobility") { RoutineRunner("Daily mobility", Routines.MOBILITY, G.Sleep, null) { nav.popBackStack() } }
        composable("workout") { WorkoutScreen { bump(); nav.popBackStack() } }
        composable("sleep") { SleepScreen(refresh) { nav.navigate(it) } }
        composable("ready") { ReadyScreen(refresh) { nav.navigate(it) } }
    }
}

// ================= SUMMARY template: Home =================
@Composable
private fun HomeScreen(refresh: Int, go: (String) -> Unit, bump: () -> Unit) {
    val ctx = LocalContext.current
    val s = rememberSnap(refresh)
    val fg = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Scheduler.registerNow(ctx); bump()
    }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Scheduler.registerNow(ctx); bump()
    }
    GScreen {
        item {
            Box(Modifier.fillMaxWidth().clickable { go("move") }, contentAlignment = Alignment.Center) {
                Rings(listOf(
                    s.move.toFloat() / Prefs.goalMove to G.Move,
                    s.exMin.toFloat() / Prefs.goalExercise to G.Exercise,
                    s.hours.toFloat() / Prefs.goalHours to G.Hours,
                ))
            }
        }
        item { Box(Modifier.clickable { go("move") }) { StatLine("MOVE", fmt(s.move), "${Prefs.goalMove} KCAL", G.Move) } }
        item { Box(Modifier.clickable { go("exercise") }) { StatLine("EXERCISE", "${s.exMin}", "${Prefs.goalExercise} MIN", G.Exercise) } }
        item { Box(Modifier.clickable { go("hours") }) { StatLine("ACTIVE HOURS", "${s.hours}", "${Prefs.goalHours} HRS", G.Hours) } }

        item {
            val w by Workout.state.collectAsState()
            if (w.active) Pill("Workout · ${dur(w.elapsedMs())}", filled = true, color = G.Exercise) { go("workout") }
            else Pill("Start workout", filled = true, color = G.Exercise) { go("workout") }
        }
        item { Pill("Timers & stretches", sub = "Intervals · desk stretch · mobility") { go("timers") } }

        if (!s.permsOk) item {
            Pill("Allow sensors", filled = true, sub = "Needed for tracking") { fg.launch(arrayOf(Passive.ACT_PERM, Passive.HR_PERM)) }
        } else if (!s.bgOk) item {
            Pill("Allow all the time", filled = true, sub = "For all-day heart rate") { bg.launch(Passive.BG_PERM) }
        }
        if (s.checkinDue) item { Pill("Morning check-in", filled = true, color = G.Sleep) { go("checkin") } }
        if (LocalTime.now().hour in 5..11 && Hrv.today() == null) item {
            Pill("Morning HRV", filled = true, color = G.Sleep, sub = "60 s · sit still") { go("hrv") }
        }

        item {
            val r = remember(refresh) { Recovery.readiness() }
            Tile("READINESS", r?.score?.toString() ?: "–", if (r != null) "/ 100" else "", readyColor(r?.score),
                r?.let { "${it.label} · ${it.parts.minByOrNull { p -> p.score }?.name?.lowercase() ?: ""} lowest" } ?: "Needs sleep or morning HRV") { go("ready") }
        }
        item { Tile("STEPS", fmt(s.steps), "%.2f KM".format(s.km), G.Steps, "${s.floors} floors") { go("hours") } }
        item { Tile("HEART RATE", s.hr, "BPM", G.Heart, if (s.hrTime.isNotEmpty()) "at ${s.hrTime}" else "Waiting for reading") { go("heart") } }
        item {
            val t = Hrv.today(); val b = Hrv.baseline()
            Tile("HRV", t?.let { "%.0f".format(it) } ?: "–", "MS", G.Sleep,
                when { t != null && b != null -> "7-day avg %.0f ms".format(b); t == null -> "Measure each morning"; else -> "Building baseline" }) { go("hrv") }
        }
        item { Tile("WATER", "%.2f".format(s.water / 1000.0), "/ %.1f L".format(Prefs.goalWater / 1000.0), G.Water) { go("water") } }
        item { Tile("SLEEP", s.sleep, if (s.sleep.first().isDigit()) "HRS" else "", G.Sleep, s.sleepDetail) { go("sleep") } }
        item { Tile("BODY", "%.1f".format(Prefs.weightKg), "KG", G.Body, Store.kvGet("body_last_summary") ?: "Weekly measurement") { go("body") } }
        item {
            val c = when { !s.configured || s.error.isNotEmpty() -> G.Warn; s.pending > 0 -> G.Accent; else -> G.Good }
            Tile("SYNC", if (s.error.isNotEmpty()) "Issue" else s.lastUpload, "", c,
                if (!s.configured) "Not linked" else "${s.pending} waiting · ${s.battery}") { go("status") }
        }
        item { Pill("Settings") { go("settings") } }
    }
}

// ================= DETAIL template =================
@Composable
private fun MoveScreen(refresh: Int) {
    val s = rememberSnap(refresh)
    GScreen {
        item { Hero("MOVE", fmt(s.move), "/${Prefs.goalMove} KCAL", G.Move) }
        item { Bars(s.hKcal, G.Move, nowHour = s.nowHour) }
        item { Tile("TOTAL BURNED", fmt(s.kcalTotal), "KCAL", G.Move, "Active + resting so far") }
        item { Caption("Resting burn ${Prefs.bmr().toInt()} kcal/day · from weight, height, age") }
    }
}

@Composable
private fun ExerciseScreen(refresh: Int) {
    val s = rememberSnap(refresh)
    GScreen {
        item { Hero("EXERCISE", "${s.exMin}", "/${Prefs.goalExercise} MIN", G.Exercise) }
        item { Bars(s.hEx, G.Exercise, nowHour = s.nowHour) }
        item { Caption("Counts minutes of brisk walking (≥ 100 steps/min) or heart rate ≥ ${Day.hrExerciseBpm().toInt()} bpm") }
    }
}

@Composable
private fun HoursScreen(refresh: Int) {
    val s = rememberSnap(refresh)
    GScreen {
        item { Hero("ACTIVE HOURS", "${s.hours}", "/${Prefs.goalHours} HRS", G.Hours) }
        item { Bars(s.hStepsList, G.Hours, highlightAt = Day.ACTIVE_HOUR_STEPS, nowHour = s.nowHour) }
        item { Tile("STEPS", fmt(s.steps), "/${fmt(Prefs.goalSteps.toLong())}", G.Steps, "%.2f km · %d floors".format(s.km, s.floors)) }
        item { Caption("An hour counts when you walk ≥ ${Day.ACTIVE_HOUR_STEPS.toInt()} steps in it") }
    }
}

@Composable
private fun HeartScreen(refresh: Int) {
    val s = rememberSnap(refresh)
    GScreen {
        item { Hero("HEART RATE", s.hr, "BPM", G.Heart, if (s.hrTime.isNotEmpty()) "at ${s.hrTime}" else "") }
        item { HrLine(s.hrLine, s.nowSlot, Day.hrMaxEst()) }
        if (s.hrRange.isNotEmpty()) item { Tile("TODAY", s.hrRange.substringBefore(" ·"), "", G.Heart, s.hrRange.substringAfter("· ")) }
        item { Bars(s.hHr, G.Heart, nowHour = s.nowHour) }
        item { Caption("Line: 5-min average · bars: hourly average") }
    }
}

// ================= LIST template =================
@Composable
private fun WaterScreen(refresh: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val s = rememberSnap(refresh)
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bump() }
    GScreen {
        item { Hero("WATER", "%.2f".format(s.water / 1000.0), "/ %.1f L".format(Prefs.goalWater / 1000.0), G.Water) }
        item { Pill("+250 ml", filled = true, color = G.Water) { Day.addWater(250); WaterNotify.cancel(ctx); bump() } }
        item { Pill("+500 ml") { Day.addWater(500); WaterNotify.cancel(ctx); bump() } }
        item { Pill("Undo last") { Day.addWater(0); bump() } }
        if (!s.notifOk) item { Pill("Allow reminders", filled = true, sub = "Every 90 min, 09–22") { perm.launch(WaterNotify.PERM) } }
        else item { Caption(if (Prefs.waterRemind) "Reminders every 90 min, 09:00–22:00" else "Reminders off (Settings)") }
    }
}

@Composable
private fun BodyScreen(onDone: () -> Unit) {
    fun last(k: String, d: Float) = Store.kvGet("body_$k")?.toFloatOrNull() ?: d
    var weight by remember { mutableStateOf(Prefs.weightKg) }
    var waist by remember { mutableStateOf(last("waist", 100f)) }
    var neck by remember { mutableStateOf(last("neck", 40f)) }
    var bf by remember { mutableStateOf(last("bf", 0f)) }          // Samsung body fat %, 0 = not entered
    var smm by remember { mutableStateOf(last("smm", 0f)) }        // Samsung skeletal muscle kg, 0 = not entered
    val navy = if (Prefs.male && waist - neck > 1f)
        495.0 / (1.0324 - 0.19077 * log10((waist - neck).toDouble()) + 0.15456 * log10(Prefs.heightCm.toDouble())) - 450.0
    else null
    GScreen {
        item { Hero("BODY FAT (TAPE)", navy?.let { "%.1f".format(it) } ?: "–", "%  · US Navy method", G.Body) }
        item { Stepper("WEIGHT KG", "%.1f".format(weight), G.Body, { weight -= 0.1f }, { weight += 0.1f }) }
        item { Stepper("WAIST CM (at navel)", "%.1f".format(waist), G.Body, { waist -= 0.5f }, { waist += 0.5f }) }
        item { Stepper("NECK CM", "%.1f".format(neck), G.Body, { neck -= 0.5f }, { neck += 0.5f }) }
        item { Caption("Optional · from Samsung Health body composition") }
        item { Stepper("SAMSUNG FAT %", if (bf > 0f) "%.1f".format(bf) else "–", G.Body,
            { bf = (bf - 0.5f).coerceAtLeast(0f) }, { bf = if (bf == 0f) 30f else bf + 0.5f }) }
        item { Stepper("MUSCLE KG", if (smm > 0f) "%.1f".format(smm) else "–", G.Body,
            { smm = (smm - 0.5f).coerceAtLeast(0f) }, { smm = if (smm == 0f) 35f else smm + 0.5f }) }
        item {
            Pill("Save", filled = true, color = G.Body) {
                val j = org.json.JSONObject().put("weight_kg", weight.toDouble()).put("waist_cm", waist.toDouble()).put("neck_cm", neck.toDouble())
                    .put("height_cm", Prefs.heightCm)
                navy?.let { j.put("bf_navy", Math.round(it * 10) / 10.0) }
                if (bf > 0f) j.put("bf_samsung", bf.toDouble())
                if (smm > 0f) j.put("smm_kg", smm.toDouble())
                Rec.bodyComp(Rec.today(), j)
                Prefs.weightKg = weight
                Store.kvSet("body_waist", waist.toString()); Store.kvSet("body_neck", neck.toString())
                Store.kvSet("body_bf", bf.toString()); Store.kvSet("body_smm", smm.toString())
                Store.kvSet("body_last_summary", "${Rec.today()} · " + (navy?.let { "%.1f%% fat".format(it) } ?: "saved"))
                onDone()
            }
        }
        item { Caption("Same day + time each week: morning, after toilet, before food") }
    }
}

@Composable
private fun StatusScreen(refresh: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val s = rememberSnap(refresh)
    GScreen {
        item { Text("Sync", style = GType.Title, color = G.Text) }
        item { Pill("Sync now", filled = true, sub = "${s.pending} waiting · last ${s.lastUpload}") { Scheduler.syncNow(ctx); bump() } }
        if (s.error.isNotEmpty()) item { Caption(s.error, G.Warn) }
        item { Tile("ACCOUNT", if (s.configured) "Linked" else "Not linked", "", if (s.configured) G.Good else G.Warn, "${Prefs.person} · ${Prefs.deviceId}") }
        item { Tile("TRACKING", if (s.passive.startsWith("on")) "On" else "Off", "", G.Steps, s.passive.removePrefix("on: ")) }
        item { Tile("BATTERY", s.battery, "", G.Good) }
        if (s.dead > 0) item {
            Pill("Retry ${s.dead} rejected rows", sub = "After a server update") { Store.reviveDead(); Scheduler.syncNow(ctx); bump() }
        }
        item { Caption("Swipe right to go back") }
    }
}

// ================= WORKOUT: picker → live → summary =================
private fun dur(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}

@Composable
private fun WorkoutScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val w by Workout.state.collectAsState()
    when {
        w.active && Session.program != null -> ProgramRunner()
        w.active -> WorkoutLive(w)
        w.summary -> { LaunchedEffect(Unit) { Session.clear() }; WorkoutSummary(w) { Workout.dismissSummary(); onClose() } }
        else -> {
            val types by produceState(initialValue = emptyList<String>()) { value = Workout.supportedTypes(ctx) }
            val gym = Programs.nextGym()
            GScreen {
                item { Text("Workout", style = GType.Title, color = G.Text) }
                if (w.msg.isNotEmpty()) item { Caption(w.msg, G.Warn) }
                item { Caption("PROGRAMS", G.Muted) }
                item { Pill("Today: ${gym.name}", filled = true, color = G.Exercise, sub = programLabel(gym)) { startProgram(ctx, gym) } }
                Programs.ALL.filter { it.id != gym.id }.forEach { p ->
                    item { Pill(p.name, color = if (p.kind == "strength") G.Exercise else G.Hours, sub = programLabel(p)) { startProgram(ctx, p) } }
                }
                item { Caption("FREE WORKOUT / SPORT", G.Muted) }
                if (types.isEmpty()) item { Caption("Loading…") }
                types.forEach { t -> item { Pill(Workout.pretty(t), color = G.Exercise) { Session.clear(); Workout.pendingProgramId = ""; Workout.start(ctx, t) } } }
            }
        }
    }
}

@Composable
private fun WorkoutLive(w: Workout.Live) {
    val ctx = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1_000) } }
    var confirm by remember { mutableStateOf(false) }
    val zone = if (w.hr > 0) Workout.zoneOf(w.hr.toDouble()) else -1
    GScreen {
        item { Hero(Workout.pretty(w.type).uppercase(), dur(w.elapsedMs(now)), if (w.paused) "PAUSED" else "ACTIVE TIME", G.Exercise, w.msg) }
        item { Tile("HEART RATE", if (w.hr > 0) "${w.hr}" else "–", "BPM", if (zone >= 0) ZoneColors[zone] else G.Heart,
            (if (zone >= 0) "Zone ${zone + 1} · " else "") + "avg ${w.avgHr} · max ${w.maxHr}") }
        item { Tile("CALORIES", "${w.kcal.toLong()}", "KCAL", G.Move) }
        if (w.steps > 0 || w.distM > 0) item { Tile("DISTANCE", "%.2f".format(w.distM / 1000.0), "KM", G.Hours, "${fmt(w.steps)} steps") }
        item { ZoneBar(w.zones) }
        item {
            if (w.paused) Pill("Resume", filled = true, color = G.Exercise) { Workout.resume(ctx) }
            else Pill("Pause") { Workout.pause(ctx) }
        }
        item {
            Pill(if (confirm) "Tap again to end" else "End", filled = confirm, color = G.Heart) {
                if (confirm) Workout.end(ctx) else confirm = true
            }
        }
    }
}

@Composable
private fun WorkoutSummary(w: Workout.Live, onDone: () -> Unit) {
    GScreen {
        item { Hero(Workout.pretty(w.type).uppercase(), dur(w.activeMs), "ACTIVE TIME · SAVED", G.Exercise) }
        item { Tile("HEART RATE", "${w.avgHr}", "AVG BPM", G.Heart, "max ${w.maxHr}") }
        item { Tile("CALORIES", "${w.kcal.toLong()}", "KCAL", G.Move) }
        if (w.distM > 0) item { Tile("DISTANCE", "%.2f".format(w.distM / 1000.0), "KM", G.Hours, "${fmt(w.steps)} steps") }
        item { ZoneBar(w.zones) }
        item { Pill("Done", filled = true, color = G.Exercise, onClick = onDone) }
        if (w.activeMs < 60_000) item { Caption("Under 1 minute: not saved") }
    }
}

// ================= HRV: 60-s morning reading =================
@Composable
private fun HrvScreen(bump: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var sec by remember { mutableIntStateOf(0) }
    var hr by remember { mutableIntStateOf(0) }
    var beats by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Hrv.Result?>(null) }
    val workout by Workout.state.collectAsState()
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    fun start() {
        running = true; result = null; sec = 0; hr = 0; beats = 0
        scope.launch {
            val r = Hrv.measure(ctx) { s, h, n -> sec = s; hr = h; beats = n }
            if (r.ok) { Hrv.save(r); bump() }
            result = r; running = false
        }
    }

    GScreen {
        val r = result
        when {
            running -> {
                item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Rings(listOf(sec / 60f to G.Sleep), size = 96.dp) } }
                item { Hero("HRV", "${60 - sec}", "SECONDS · KEEP STILL", G.Sleep, "HR ${if (hr > 0) hr else "–"} · $beats beats") }
            }
            r != null && r.ok -> {
                val b = Hrv.baseline()
                item { Hero("HRV", "%.0f".format(r.rmssd), "MS · RMSSD", G.Sleep, "HR ${r.hr} · ${r.beats} beats · saved") }
                item {
                    if (b != null) {
                        val pct = (r.rmssd / b - 1) * 100
                        Tile("VS YOUR 7-DAY AVG", "%+.0f".format(pct), "%", G.Sleep,
                            "avg %.0f ms · ".format(b) + when { pct <= -15 -> "below normal: go easy"; pct >= 15 -> "above normal: well recovered"; else -> "normal range" })
                    } else Tile("BASELINE", "Building", "", G.Sleep, "Needs 3+ mornings")
                }
                item { Pill("Measure again") { start() } }
            }
            else -> {
                item { Hero("HRV", r?.let { "–" } ?: "60", if (r == null) "SECOND READING" else "NOT SAVED", G.Sleep, r?.error ?: "") }
                item { Caption("Morning, after waking, before coffee. Sit, rest arm on a table, don't talk.") }
                if (workout.active) item { Caption("Finish the workout first", G.Warn) }
                else item { Pill(if (r == null) "Start" else "Retry", filled = true, color = G.Sleep) { start() } }
            }
        }
    }
}

// ================= v1.7: Sleep + Readiness =================
private fun readyColor(score: Int?) = when { score == null -> G.Muted; score >= 75 -> G.Good; score >= 50 -> G.Accent; else -> G.Warn }
private fun hmin(h: Double): String { val m = (h * 60).roundToLong(); return "${m / 60}h %02dm".format(m % 60) }

@Composable
private fun SleepScreen(refresh: Int, go: (String) -> Unit) {
    val today = Rec.today()
    val n = remember(refresh) { Recovery.night(today) }
    val week = remember(refresh) { Recovery.nights(7) }
    val hr = remember(refresh, n) { n?.let { Recovery.hrBetween(it.start, it.end) } }
    val rhr = remember(refresh) { Rec.dailyValue(today, "resting_hr")?.roundToLong()?.toInt() ?: Recovery.restingHr(today) }
    val rb = remember(refresh) { Recovery.rhrBaseline(today) }
    val open = Store.kvGet("sleep_start")?.toLongOrNull()
    val hrs = week.mapNotNull { it.second?.hours }
    GScreen {
        when {
            open != null -> item { Hero("SLEEP", "Asleep", "SINCE ${hm(open)}", G.Sleep) }
            n != null -> item { Hero("LAST NIGHT", hmin(n.hours), "${hm(n.start)} – ${hm(n.end)}", G.Sleep,
                if (Store.kvGet("sleep_user:$today") != null) "Set in check-in" else "Detected by watch") }
            else -> item { Hero("LAST NIGHT", "–", "NOT RECORDED", G.Sleep, "Wear the watch to bed, or set it in check-in") }
        }
        if (hr != null) item {
            // 5-min averages through the night
            val pts = hr.toList().chunked(5).map { c -> c.filter { it > 0 }.let { f -> if (f.isEmpty()) 0 else f.average().roundToLong().toInt() } }
            MiniLine(pts, G.Heart, hm(n!!.start), hm(n.end))
        }
        item { Tile("RESTING HR", rhr?.toString() ?: "–", "BPM", G.Heart,
            when { rhr != null && rb != null -> "7-day avg %.0f · %+.0f".format(rb, rhr - rb); rhr != null -> "Lowest 15 min while asleep"; else -> "After a night with the watch on" }) }
        item { Caption("LAST 7 NIGHTS", G.Muted) }
        item { DayBars(week.map { it.second?.hours ?: 0.0 }, week.map { it.first.dayOfWeek.name.take(1) }, G.Sleep, goal = 7.5) }
        if (hrs.isNotEmpty()) item { Caption("Average ${hmin(hrs.average())} · goal 7h 30m") }
        item { Pill("Edit last night", sub = "Morning check-in") { go("checkin") } }
    }
}

@Composable
private fun ReadyScreen(refresh: Int, go: (String) -> Unit) {
    val r = remember(refresh) { Recovery.readiness() }
    GScreen {
        if (r == null) {
            item { Hero("READINESS", "–", "NOT ENOUGH DATA", G.Muted) }
            item { Caption("Needs last night's sleep or a morning HRV reading. Resting HR and HRV need 3 days to build a baseline.") }
            item { Pill("Morning HRV", filled = true, color = G.Sleep, sub = "60 s · sit still") { go("hrv") } }
        } else {
            val c = readyColor(r.score)
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Rings(listOf(r.score / 100f to c), size = 96.dp, stroke = 11.dp) } }
            item { Hero("READINESS", "${r.score}", r.label.uppercase(), c, when {
                r.score >= 75 -> "Good day for a full session"
                r.score >= 50 -> "Train as planned"
                else -> "Lighter weights, Zone 2, or mobility" }) }
            r.parts.forEach { p -> item { Tile(p.name.uppercase(), "${p.score}", "/ 100", readyColor(p.score), "${p.detail} · weight ${p.weight}%") } }
            if (r.parts.size < 4) item { Caption("More parts appear as data builds: HRV and resting HR need 3 days.") }
        }
        item { Pill("Sleep details", color = G.Sleep) { go("sleep") } }
    }
}

// ---------------- Morning check-in ----------------
@Composable
private fun CheckinScreen(onDone: () -> Unit) {
    val today = LocalDate.now(Rec.zone)
    val detected = remember { Store.kvGet("last_sleep")?.split("-")?.mapNotNull { it.toLongOrNull() } }
    var bed by remember {
        mutableLongStateOf(detected?.getOrNull(0) ?: today.minusDays(1).atTime(23, 30).atZone(Rec.zone).toInstant().toEpochMilli())
    }
    var wake by remember {
        mutableLongStateOf(detected?.getOrNull(1) ?: today.atTime(6, 45).atZone(Rec.zone).toInstant().toEpochMilli())
    }
    var rested by remember { mutableIntStateOf(-1) }
    val step = 15 * 60_000L

    GScreen {
        item { Text("Last night", style = GType.Title, color = G.Text) }
        item { Caption(if (detected != null) "Detected by watch · adjust if wrong" else "Not detected · set it") }
        item { Stepper("BED", hm(bed), G.Sleep, { bed -= step }, { bed += step }) }
        item { Stepper("WOKE", hm(wake), G.Sleep, { wake -= step }, { wake += step }) }
        item { Caption("How rested? 1 = awful · 5 = great") }
        item { RoundButtons(listOf("1", "2", "3", "4", "5"), selected = rested, color = G.Sleep) { rested = it } }
        item {
            Pill(if (rested < 0) "Pick 1–5" else "Save", filled = rested >= 0, color = G.Sleep) {
                if (rested >= 0 && wake > bed) { Rec.checkin(today, bed, wake, rested + 1); Recovery.recordNight(bed, wake, userSet = true); onDone() }
            }
        }
    }
}

// ---------------- Settings ----------------
@Composable
private fun SettingsScreen(bump: () -> Unit) {
    val ctx = LocalContext.current
    var power by remember { mutableStateOf(Prefs.powerMode) }
    var sync by remember { mutableStateOf(Prefs.syncMode) }
    var saver by remember { mutableStateOf(Prefs.autoSaver) }
    var remind by remember { mutableStateOf(Prefs.waterRemind) }
    var moveR by remember { mutableStateOf(Prefs.moveRemind) }
    var move by remember { mutableIntStateOf(Prefs.goalMove) }
    var ex by remember { mutableIntStateOf(Prefs.goalExercise) }
    var hrs by remember { mutableIntStateOf(Prefs.goalHours) }
    var steps by remember { mutableIntStateOf(Prefs.goalSteps) }
    var water by remember { mutableIntStateOf(Prefs.goalWater) }
    val syncLabel = mapOf("CHARGING" to "While charging", "H1" to "Every 1 h", "H3" to "Every 3 h", "H6" to "Every 6 h")
    val syncOrder = listOf("CHARGING", "H1", "H3", "H6")

    GScreen {
        item { Text("Settings", style = GType.Title, color = G.Text) }
        item { Caption("GOALS", G.Muted) }
        item { Stepper("MOVE KCAL", "$move", G.Move, { move = (move - 50).coerceAtLeast(100); Prefs.goalMove = move; bump() },
            { move = (move + 50).coerceAtMost(2000); Prefs.goalMove = move; bump() }) }
        item { Stepper("EXERCISE MIN", "$ex", G.Exercise, { ex = (ex - 5).coerceAtLeast(5); Prefs.goalExercise = ex; bump() },
            { ex = (ex + 5).coerceAtMost(180); Prefs.goalExercise = ex; bump() }) }
        item { Stepper("ACTIVE HOURS", "$hrs", G.Hours, { hrs = (hrs - 1).coerceAtLeast(4); Prefs.goalHours = hrs; bump() },
            { hrs = (hrs + 1).coerceAtMost(18); Prefs.goalHours = hrs; bump() }) }
        item { Stepper("STEPS", fmt(steps.toLong()), G.Steps, { steps = (steps - 500).coerceAtLeast(1000); Prefs.goalSteps = steps; bump() },
            { steps = (steps + 500).coerceAtMost(40000); Prefs.goalSteps = steps; bump() }) }
        item { Stepper("WATER L", "%.2f".format(water / 1000.0), G.Water, { water = (water - 250).coerceAtLeast(1000); Prefs.goalWater = water; bump() },
            { water = (water + 250).coerceAtMost(6000); Prefs.goalWater = water; bump() }) }
        item { Caption("SYSTEM", G.Muted) }
        item {
            Pill("Power: ${if (power == "FULL") "Full" else "Essential"}",
                sub = if (power == "FULL") "Steps, kcal, floors, km, HR, sleep" else "Steps, kcal, sleep") {
                power = if (power == "FULL") "ESSENTIAL" else "FULL"
                Prefs.powerMode = power; Store.kvDel("saver_prev"); Scheduler.registerNow(ctx); bump()
            }
        }
        item {
            Pill("Sync: ${syncLabel[sync]}", sub = if (sync == "CHARGING") "+ daily fallback" else "Over Wi-Fi") {
                sync = syncOrder[(syncOrder.indexOf(sync) + 1) % syncOrder.size]
                Prefs.syncMode = sync; Scheduler.apply(ctx)
            }
        }
        item { Pill("Auto saver: ${if (saver) "On" else "Off"}", sub = "Essential below 20%") { saver = !saver; Prefs.autoSaver = saver } }
        item { Pill("Water reminders: ${if (remind) "On" else "Off"}") { remind = !remind; Prefs.waterRemind = remind } }
        item { Pill("Move reminders: ${if (moveR) "On" else "Off"}", sub = ":50 if < 250 steps that hour") {
            moveR = !moveR; Prefs.moveRemind = moveR; dev.ghar.watch.system.MoveReminder.schedule(ctx) } }
        item { Pill("Restart tracking") { Scheduler.registerNow(ctx) } }
        item { Caption("Heart-rate interval: Samsung Health → Heart rate settings") }
        item { Caption("Android ${Build.VERSION.RELEASE} · ${Rec.APP_VERSION}", G.Dim) }
    }
}
