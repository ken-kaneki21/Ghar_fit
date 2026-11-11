package dev.ghar.watch.presentation

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import dev.ghar.watch.presentation.ui.Caption
import dev.ghar.watch.presentation.ui.G
import dev.ghar.watch.presentation.ui.GScreen
import dev.ghar.watch.presentation.ui.GType
import dev.ghar.watch.presentation.ui.Hero
import dev.ghar.watch.presentation.ui.Pill
import dev.ghar.watch.presentation.ui.Rings
import dev.ghar.watch.presentation.ui.Stepper
import dev.ghar.watch.workout.Workout
import kotlinx.coroutines.delay

/** One timed step of a routine. kind: work / rest / move (stretch, mobility). */
data class TStep(val label: String, val secs: Int, val kind: String = "move")

object Routines {
    fun intervals(work: Int, rest: Int, rounds: Int, workLabel: String = "Work"): List<TStep> {
        val out = mutableListOf(TStep("Get ready", 10, "rest"))
        for (r in 1..rounds) {
            out += TStep("$workLabel $r/$rounds", work, "work")
            if (r < rounds && rest > 0) out += TStep("Rest", rest, "rest")
        }
        return out
    }

    /** 2-min desk stretch (handover §10): neck, shoulders, chest, hips. */
    val DESK = listOf(
        TStep("Stand up, shake out", 10), TStep("Neck tilt · left", 15), TStep("Neck tilt · right", 15),
        TStep("Shoulder rolls back", 15), TStep("Chest opener (hands behind)", 20),
        TStep("Hip flexor · left", 20), TStep("Hip flexor · right", 20), TStep("Reach up, side bend", 15),
    )

    /** 10-min daily mobility (handover §10): cat-cow, child's pose, chin tucks, wall angels, doorway chest, McGill Big 3. */
    val MOBILITY = listOf(
        TStep("Cat-cow", 60), TStep("Child's pose", 60), TStep("Chin tucks", 45), TStep("Wall angels", 60),
        TStep("Doorway chest · left", 30), TStep("Doorway chest · right", 30),
        TStep("Bird-dog · left", 40), TStep("Bird-dog · right", 40), TStep("Dead bug", 60),
        TStep("Side plank · left", 30), TStep("Side plank · right", 30),
        TStep("Curl-up (McGill)", 45), TStep("Breathe, relax", 40),
    )
}

private fun buzz(ctx: Context, ms: Long) = runCatching {
    val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return@runCatching
    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}

private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)

/**
 * Runs a routine: big countdown, step name, next step, buzz on every switch (long), 3-2-1 (short),
 * double buzz at the end. Drift-free (elapsedRealtime), pause/resume, screen kept on.
 * recordType: start a Health Services workout alongside (e.g. HIIT) and end it when the routine ends.
 */
@Composable
fun RoutineRunner(
    title: String, steps: List<TStep>, color: Color, recordType: String?,
    autoClose: Boolean = false,            // program phases: hand straight back when done
    hrBand: IntRange? = null,              // cardio: show live HR, buzz if outside the band > 60 s (2-min cooldown)
    stopLabel: String = "Stop",
    onStep: (Int) -> Unit = {},            // number of steps completed so far
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val live by Workout.state.collectAsState()
    var outSince by remember { mutableLongStateOf(0L) }
    var lastWarn by remember { mutableLongStateOf(0L) }
    val view = LocalView.current
    var idx by remember { mutableIntStateOf(0) }
    var endAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + steps[0].secs * 1000L) }
    var paused by remember { mutableStateOf(false) }
    var pausedLeft by remember { mutableLongStateOf(0L) }
    var left by remember { mutableIntStateOf(steps[0].secs) }
    var finished by remember { mutableStateOf(false) }
    var lastBeep by remember { mutableIntStateOf(-1) }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        if (recordType != null && !Workout.state.value.active) Workout.start(ctx, recordType)
        buzz(ctx, 300)
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(idx, paused, finished) {
        while (!paused && !finished) {
            val ms = endAt - SystemClock.elapsedRealtime()
            val s = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
            left = s
            if (s in 1..3 && s != lastBeep) { lastBeep = s; buzz(ctx, 70) }
            if (ms <= 0) {
                onStep(idx + 1)
                if (idx + 1 < steps.size) {
                    idx += 1; lastBeep = -1
                    endAt = SystemClock.elapsedRealtime() + steps[idx].secs * 1000L
                    buzz(ctx, if (steps[idx].kind == "work") 500 else 300)
                } else {
                    finished = true
                    buzz(ctx, 250); delay(350); buzz(ctx, 250)
                    if (recordType != null && Workout.state.value.active) Workout.end(ctx)
                    if (autoClose) onDone()
                }
                break
            }
            delay(200)
        }
    }
    // Zone guard: only for work steps (the main block), checked on each HR update.
    LaunchedEffect(live.hr, idx, paused) {
        val band = hrBand ?: return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        val inWork = steps.getOrNull(idx)?.kind == "work"
        if (paused || !inWork || live.hr <= 0 || live.hr in band) { outSince = 0L; return@LaunchedEffect }
        if (outSince == 0L) outSince = now
        if (now - outSince > 60_000 && now - lastWarn > 120_000) {
            lastWarn = now
            buzz(ctx, 150); delay(250); buzz(ctx, 150); delay(250); buzz(ctx, 150)
        }
    }

    val st = steps[idx]
    val c = when (st.kind) { "work" -> color; "rest" -> G.Water; else -> color }
    val total = steps.sumOf { it.secs }
    val doneSecs = steps.take(idx).sumOf { it.secs } + (st.secs - left)
    GScreen {
        if (finished) {
            item { Hero(title.uppercase(), mmss(total), "DONE", color, if (recordType != null) "Saved as a workout" else "") }
            item { Pill("Close", filled = true, color = color) { onDone() } }
        } else {
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Rings(listOf((st.secs - left).toFloat() / st.secs.coerceAtLeast(1) to c, doneSecs.toFloat() / total to G.Dim), size = 92.dp, stroke = 10.dp)
            } }
            item { Hero(st.label.uppercase(), mmss(left), if (paused) "PAUSED" else "STEP ${idx + 1}/${steps.size}", c,
                steps.getOrNull(idx + 1)?.let { "Next: ${it.label}" } ?: "Last step") }
            if (hrBand != null) item {
                val h = live.hr
                val msg = when { h <= 0 -> "Reading heart rate…"; h < hrBand.first -> "Below Zone 2 · go a bit faster / steeper"
                    h > hrBand.last -> "Above Zone 2 · slow down"; else -> "In Zone 2 ✓" }
                Text("♥ ${if (h > 0) h else "–"} bpm  ·  Z2 ${hrBand.first}–${hrBand.last}", style = GType.Body,
                    color = if (h in hrBand) G.Good else if (h > 0) G.Warn else G.Muted)
                Caption(msg)
            }
            item {
                if (paused) Pill("Resume", filled = true, color = color) { endAt = SystemClock.elapsedRealtime() + pausedLeft; paused = false }
                else Pill("Pause") { pausedLeft = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0); paused = true }
            }
            item { Pill("Skip step") { endAt = SystemClock.elapsedRealtime() } }
            item {
                Pill(stopLabel, color = G.Heart) {
                    finished = true
                    if (recordType != null && Workout.state.value.active) Workout.end(ctx)
                    onDone()
                }
            }
        }
    }
}

/** Interval timer setup: presets + custom, optional workout recording. */
@Composable
fun IntervalScreen(onClose: () -> Unit) {
    var work by remember { mutableIntStateOf(40) }
    var rest by remember { mutableIntStateOf(20) }
    var rounds by remember { mutableIntStateOf(8) }
    var record by remember { mutableStateOf(true) }
    var type by remember { mutableStateOf("HIGH_INTENSITY_INTERVAL_TRAINING") }
    var label by remember { mutableStateOf("Work") }
    var running by remember { mutableStateOf(false) }
    if (running) {
        RoutineRunner("Intervals", Routines.intervals(work, rest, rounds, label), G.Exercise, if (record) type else null) { running = false; onClose() }
        return
    }
    fun preset(w: Int, r: Int, n: Int, t: String, l: String) { work = w; rest = r; rounds = n; type = t; label = l; running = true }
    GScreen {
        item { Text("Interval timer", style = GType.Title, color = G.Text) }
        item { Pill("Skipping 30/30 × 10", color = G.Exercise) { preset(30, 30, 10, "JUMP_ROPE", "Skip") } }
        item { Pill("HIIT 40/20 × 8", color = G.Exercise) { preset(40, 20, 8, "HIGH_INTENSITY_INTERVAL_TRAINING", "Work") } }
        item { Pill("Tabata 20/10 × 8", color = G.Exercise) { preset(20, 10, 8, "HIGH_INTENSITY_INTERVAL_TRAINING", "Work") } }
        item { Pill("Push-up EMOM × 10", sub = "45 s set + 15 s rest", color = G.Exercise) { preset(45, 15, 10, "STRENGTH_TRAINING", "Push-ups") } }
        item { Caption("CUSTOM", G.Muted) }
        item { Stepper("WORK", mmss(work), G.Exercise, { work = (work - 5).coerceAtLeast(5) }, { work = (work + 5).coerceAtMost(600) }) }
        item { Stepper("REST", mmss(rest), G.Water, { rest = (rest - 5).coerceAtLeast(0) }, { rest = (rest + 5).coerceAtMost(300) }) }
        item { Stepper("ROUNDS", "$rounds", G.Exercise, { rounds = (rounds - 1).coerceAtLeast(1) }, { rounds = (rounds + 1).coerceAtMost(50) }) }
        item { Pill("Record as workout: ${if (record) "On" else "Off"}") { record = !record } }
        item { Pill("Start custom", filled = true, color = G.Exercise, sub = "Total ${mmss(10 + rounds * work + (rounds - 1) * rest)}") {
            type = "HIGH_INTENSITY_INTERVAL_TRAINING"; label = "Work"; running = true } }
    }
}

/** List of timers and guided routines. */
@Composable
fun TimersScreen(go: (String) -> Unit) {
    GScreen {
        item { Text("Timers", style = GType.Title, color = G.Text) }
        item { Pill("Interval timer", filled = true, color = G.Exercise, sub = "Skipping · HIIT · Tabata · custom") { go("interval") } }
        item { Pill("Desk stretch", color = G.Sleep, sub = "2 min · neck, shoulders, hips") { go("stretch") } }
        item { Pill("Daily mobility", color = G.Sleep, sub = "10 min · incl. McGill Big 3") { go("mobility") } }
        item { Caption("Buzz on every switch, 3-2-1 before it. Screen stays on.") }
    }
}
