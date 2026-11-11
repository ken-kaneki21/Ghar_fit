package dev.ghar.watch.presentation

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.wear.compose.material.Text
import dev.ghar.watch.presentation.ui.Caption
import dev.ghar.watch.presentation.ui.G
import dev.ghar.watch.presentation.ui.GScreen
import dev.ghar.watch.presentation.ui.GType
import dev.ghar.watch.presentation.ui.Hero
import dev.ghar.watch.presentation.ui.Pill
import dev.ghar.watch.presentation.ui.Stepper
import dev.ghar.watch.workout.Ex
import dev.ghar.watch.workout.Program
import dev.ghar.watch.workout.Programs
import dev.ghar.watch.workout.Session
import dev.ghar.watch.workout.Workout
import kotlinx.coroutines.delay

private fun pbuzz(ctx: Context, ms: Long) = runCatching {
    ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}

private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)
private fun kgStr(k: Double) = if (k % 1.0 == 0.0) "${k.toInt()}" else "%.1f".format(k)

/** Start a program: session in memory + Health Services workout tagged with the program id. */
fun startProgram(ctx: Context, p: Program) {
    Session.start(p)
    Workout.pendingProgramId = p.id
    Workout.start(ctx, p.exerciseType)
}

/** Finish: attach sets + body split, advance the gym rotation, end the workout (saved by WorkoutService). */
fun finishProgram(ctx: Context) {
    val p = Session.program
    Workout.extra = Session.buildExtra()
    if (p != null) Programs.advanceGym(p)
    Workout.end(ctx)
    Session.clear()
}

/** Program picker block for the Workout screen. */
fun programLabel(p: Program): String = when (p.kind) {
    "strength" -> "${p.exercises.size} exercises" + (p.segments.firstOrNull()?.let { " + treadmill ${it.min} min" } ?: "")
    else -> "${p.segments.sumOf { it.min }} min" + if (p.segments.any { it.zone2 }) " · Zone 2 alerts" else ""
}

/** Runs the current Session phase by phase while the workout records in the background. */
@Composable
fun ProgramRunner() {
    val ctx = LocalContext.current
    val p = Session.program ?: return
    val phase = Session.phase
    if (phase == "done") { DoneScreen(p) ; return }
    key(phase) {
        when (phase) {
            "warmup" -> RoutineRunner("Warm-up", p.warmup, G.Warn, null, autoClose = true, stopLabel = "Skip warm-up") { Session.nextPhase() }
            "strength" -> StrengthScreen(p)
            "cardio" -> {
                val steps = p.segments.map { TStep(it.name, it.min * 60, if (it.zone2 || p.kind == "dance" && it.met >= 5) "work" else "move") }
                var reached by remember { mutableIntStateOf(0) }
                RoutineRunner(if (p.kind == "dance") "Just Dance" else "Cardio", steps, G.Exercise, null, autoClose = true,
                    hrBand = if (p.segments.any { it.zone2 }) Session.zone2() else null, stopLabel = "End cardio",
                    onStep = { reached = it }) {
                    // credit finished segments; a stopped segment counts if more than half done is not tracked → finished ones only
                    Session.segDone.clear(); Session.segDone += p.segments.take(maxOf(reached, 0))
                    Session.nextPhase()
                }
            }
            "stretch" -> RoutineRunner("Stretch", p.stretch.ifEmpty { listOf(TStep("Breathe, relax", 30)) }, G.Sleep, null,
                autoClose = true, stopLabel = "Skip stretch") { Session.nextPhase() }
        }
    }
}

@Composable
private fun DoneScreen(p: Program) {
    val ctx = LocalContext.current
    val sets = Session.logs.size
    GScreen {
        item { Hero(p.name.substringBefore(" ·").uppercase(), "$sets", "SETS LOGGED", G.Exercise,
            "${Session.segDone.sumOf { it.min }} min cardio") }
        item { Pill("Save workout", filled = true, color = G.Exercise) { finishProgram(ctx) } }
        if (p.id.startsWith("gym-")) item { Caption("Next gym day: ${nextAfter(p)}") }
    }
}

private fun nextAfter(p: Program) = when (p.id) { "gym-a" -> "B · Lower + core"; "gym-b" -> "C · Full light"; else -> "A · Upper" }

@Composable
private fun StrengthScreen(p: Program) {
    val e = Session.ex() ?: run { LaunchedEffect(Unit) { Session.nextPhase() }; return }
    if (Session.resting) { key(Session.exIdx, Session.setIdx) { RestScreen(e) }; return }
    key(Session.exIdx) { SetScreen(p, e) }
}

@Composable
private fun SetScreen(p: Program, e: Ex) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val live by Workout.state.collectAsState()
    val up = Session.shouldIncrease(e)
    val last = Session.lastKg(e)
    var kg by remember { mutableDoubleStateOf(if (up && last > 0) last + 2.5 else last) }
    val isHold = e.holdS > 0
    var reps by remember { mutableIntStateOf(if (isHold) e.holdS else e.repHi) }
    var confirm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    val target = when { isHold -> "${e.holdS} s hold"; e.repLo == e.repHi -> "${e.repLo} reps"; else -> "${e.repLo}–${e.repHi} reps" } +
        if (e.perSide) " / side" else ""
    GScreen {
        item { Caption("${p.short} · EXERCISE ${Session.exIdx + 1}/${p.exercises.size}", G.Muted) }
        item { Hero(e.name.uppercase(), "Set ${Session.setIdx}/${e.sets}", target.uppercase(), G.Exercise,
            if (live.hr > 0) "♥ ${live.hr} bpm" else "") }
        if (up && e.weighted) item { Caption("All sets hit ${e.repHi} last time → +2.5 kg today", G.Good) }
        else if (e.tip.isNotEmpty()) item { Caption(e.tip) }
        if (e.weighted) item { Stepper("KG", kgStr(kg), G.Body, { kg = (kg - 2.5).coerceAtLeast(0.0) }, { kg = (kg + 2.5).coerceAtMost(300.0) }) }
        item {
            if (isHold) Stepper("SECONDS", "$reps", G.Exercise, { reps = (reps - 5).coerceAtLeast(5) }, { reps = (reps + 5).coerceAtMost(300) })
            else Stepper("REPS", "$reps", G.Exercise, { reps = (reps - 1).coerceAtLeast(1) }, { reps = (reps + 1).coerceAtMost(50) })
        }
        item { Pill("Set done", filled = true, color = G.Exercise) { pbuzz(ctx, 80); Session.logSet(e, reps, if (e.weighted) kg else 0.0) } }
        item { Pill("Skip exercise") { Session.skipExercise() } }
        item { Pill(if (confirm) "Tap again: finish now" else "Finish workout", filled = confirm, color = G.Heart) {
            if (confirm) Session.phase = "done" else confirm = true } }
        if (Session.setIdx == 1 && Session.lastKg(e) > 0 && !up) item { Caption("Last time: ${kgStr(Session.lastKg(e))} kg") }
    }
}

@Composable
private fun RestScreen(next: Ex) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val live by Workout.state.collectAsState()
    var endAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + next.restS * 1000L) }
    var left by remember { mutableIntStateOf(next.restS) }
    var lastBeep by remember { mutableIntStateOf(-1) }
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(endAt) {
        while (true) {
            val ms = endAt - SystemClock.elapsedRealtime()
            val s = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
            left = s
            if (s in 1..3 && s != lastBeep) { lastBeep = s; pbuzz(ctx, 70) }
            if (ms <= 0) { pbuzz(ctx, 400); Session.resting = false; break }
            delay(200)
        }
    }
    GScreen {
        item { Hero("REST", mmss(left), "NEXT: SET ${Session.setIdx}/${next.sets}", G.Water, next.name) }
        if (live.hr > 0) item { Text("♥ ${live.hr} bpm", style = GType.Body, color = G.Heart) }
        item { Pill("Skip rest", filled = true, color = G.Exercise) { Session.resting = false } }
        item { Pill("+15 s") { endAt += 15_000; lastBeep = -1 } }
    }
}
