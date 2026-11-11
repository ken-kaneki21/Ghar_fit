package dev.ghar.watch.workout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.wear.ongoing.OngoingActivity
import dev.ghar.watch.R
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import dev.ghar.watch.presentation.MainActivity
import dev.ghar.watch.tile.RingsTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

/** Live workout state shared with the UI (same process). Also mirrored to kv "wo_live" so a killed app can recover. */
object Workout {
    data class Live(
        val active: Boolean = false, val type: String = "", val startMs: Long = 0,
        val activeMs: Long = 0, val checkpointAt: Long = 0, val paused: Boolean = false,
        val hr: Int = 0, val avgHr: Int = 0, val maxHr: Int = 0,
        val kcal: Double = 0.0, val steps: Long = 0, val distM: Double = 0.0,
        val zones: List<Long> = List(5) { 0L },            // seconds in zone 1..5
        val summary: Boolean = false, val msg: String = "", val programId: String = "",
    ) {
        fun elapsedMs(now: Long = System.currentTimeMillis()) = activeMs + if (active && !paused && checkpointAt > 0) now - checkpointAt else 0
    }

    val state = MutableStateFlow(Live())

    /** Set by the program runner before start/end: program id, and sets + body split to save with the workout. */
    @Volatile var pendingProgramId: String = ""
    @Volatile var extra: JSONObject? = null

    /** Preferred order; only types this watch supports are shown. */
    val PREFERRED = listOf("WALKING", "RUNNING", "STRENGTH_TRAINING", "HIGH_INTENSITY_INTERVAL_TRAINING",
        "BADMINTON", "SWIMMING_POOL", "BOXING",
        "SOCCER", "JUMP_ROPE", "DANCING", "YOGA", "BIKING", "STAIR_CLIMBING", "WORKOUT")

    fun pretty(t: String) = when (t) {
        "HIGH_INTENSITY_INTERVAL_TRAINING" -> "HIIT"
        "SOCCER" -> "Football"
        "STRENGTH_TRAINING" -> "Strength"
        "JUMP_ROPE" -> "Skipping"
        "STAIR_CLIMBING" -> "Stairs"
        "WORKOUT" -> "Other workout"
        "SWIMMING_POOL" -> "Swimming"
        else -> t.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    suspend fun supportedTypes(ctx: Context): List<String> = runCatching {
        val caps = HealthServices.getClient(ctx).exerciseClient.getCapabilitiesAsync().await()
        val names = caps.supportedExerciseTypes.map { it.name }.toSet()
        PREFERRED.filter { it in names }.ifEmpty { listOf("WORKOUT") }
    }.getOrDefault(listOf("WALKING", "RUNNING", "WORKOUT"))

    fun zoneOf(bpm: Double): Int {
        val pct = bpm / Day.hrMaxEst()
        return when { pct < 0.6 -> 0; pct < 0.7 -> 1; pct < 0.8 -> 2; pct < 0.9 -> 3; else -> 4 }
    }

    private fun send(ctx: Context, action: String, type: String? = null) {
        val i = Intent(ctx, WorkoutService::class.java).setAction(action)
        if (type != null) i.putExtra("type", type)
        ContextCompat.startForegroundService(ctx, i)
    }
    fun start(ctx: Context, type: String) = send(ctx, WorkoutService.START, type)
    fun pause(ctx: Context) = send(ctx, WorkoutService.PAUSE)
    fun resume(ctx: Context) = send(ctx, WorkoutService.RESUME)
    fun end(ctx: Context) = send(ctx, WorkoutService.END)

    /** App reopened after being killed mid-workout: re-attach to Health Services. */
    fun recoverIfNeeded(ctx: Context) {
        Store.init(ctx)
        if (!state.value.active && Store.kvGet("wo_live") != null) send(ctx, WorkoutService.ATTACH)
    }
    fun dismissSummary() { state.value = Live() }
}

/**
 * Foreground service that owns the Health Services exercise (screen off / app closed keeps recording).
 * Health Services does the sensor work; we mirror metrics, count HR zones, and save one `workouts` row at the end.
 */
class WorkoutService : Service() {
    companion object {
        const val START = "start"; const val PAUSE = "pause"; const val RESUME = "resume"; const val END = "end"; const val ATTACH = "attach"
        private const val NOTIF_ID = 4301
        private const val CH = "workout"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: ExerciseClient
    private var finished = false
    private var lastHrMs = 0L
    private var hrSum = 0.0
    private var hrN = 0
    private var lastSave = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        client = HealthServices.getClient(this).exerciseClient
        restore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            START -> scope.launch { startExercise(intent.getStringExtra("type") ?: "WORKOUT") }
            PAUSE -> scope.launch { runCatching { client.pauseExerciseAsync().await() } }
            RESUME -> scope.launch { runCatching { client.resumeExerciseAsync().await() } }
            END -> scope.launch {
                runCatching { client.endExerciseAsync().await() }
                delay(4_000)
                if (!finished) finish()          // Health Services never confirmed (e.g. exercise already gone): save what we have
            }
            else -> {                            // ATTACH or system restart (START_STICKY)
                if (Store.kvGet("wo_live") == null) { stopSelf(); return START_NOT_STICKY }
                client.setUpdateCallback(callback)
            }
        }
        return START_STICKY
    }

    private suspend fun startExercise(typeName: String) {
        try {
            val caps = client.getCapabilitiesAsync().await()
            val type = caps.supportedExerciseTypes.firstOrNull { it.name == typeName } ?: ExerciseType.WORKOUT
            val tc = caps.getExerciseTypeCapabilities(type)
            val want = setOf<DataType<*, *>>(
                DataType.HEART_RATE_BPM, DataType.HEART_RATE_BPM_STATS,
                DataType.CALORIES_TOTAL, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL,
            ).filter { it in tc.supportedDataTypes }.toSet()
            val autoPause = tc.supportsAutoPauseAndResume && type.name in setOf("WALKING", "RUNNING")
            val cfg = ExerciseConfig.builder(type)
                .setDataTypes(want)
                .setIsAutoPauseAndResumeEnabled(autoPause)
                .setIsGpsEnabled(false)
                .build()
            finished = false; hrSum = 0.0; hrN = 0; lastHrMs = 0L
            val now = System.currentTimeMillis()
            Workout.state.value = Workout.Live(active = true, type = type.name, startMs = now, checkpointAt = now, msg = "Starting…",
                programId = Workout.pendingProgramId)
            Workout.pendingProgramId = ""
            save(force = true)
            client.setUpdateCallback(callback)
            client.startExerciseAsync(cfg).await()
        } catch (e: Exception) {
            Workout.state.value = Workout.Live(msg = "Couldn't start: ${e.message?.take(80)}")
            Store.kvDel("wo_live")
            stopSelf()
        }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val s = Workout.state.value
            val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            val m = update.latestMetrics

            // HR samples: zones (time between samples, capped at 5 s), own avg/max fallback, and the day's minute HR line.
            val hrPts = m.getData(DataType.HEART_RATE_BPM).map { it.getTimeInstant(boot).toEpochMilli() to it.value }
                .filter { it.second in 25.0..230.0 }.sortedBy { it.first }
            val zones = s.zones.toMutableList()
            var maxHr = s.maxHr
            for ((ms, v) in hrPts) {
                if (lastHrMs > 0 && ms > lastHrMs) zones[Workout.zoneOf(v)] = zones[Workout.zoneOf(v)] + minOf(5_000L, ms - lastHrMs) / 1000
                lastHrMs = ms; hrSum += v; hrN++; maxHr = maxOf(maxHr, v.roundToInt())
            }
            if (hrPts.isNotEmpty()) runCatching { Day.onHeartRate(hrPts) }
            val stats = m.getData(DataType.HEART_RATE_BPM_STATS)

            val st = update.exerciseStateInfo.state
            val cp = update.activeDurationCheckpoint
            Workout.state.value = s.copy(
                active = !st.isEnded,
                paused = st.isPaused,
                activeMs = cp?.activeDuration?.toMillis() ?: s.activeMs,
                checkpointAt = cp?.time?.toEpochMilli() ?: s.checkpointAt,
                startMs = update.startTime?.toEpochMilli() ?: s.startMs,
                hr = hrPts.lastOrNull()?.second?.roundToInt() ?: s.hr,
                avgHr = stats?.average?.roundToInt() ?: if (hrN > 0) (hrSum / hrN).roundToInt() else s.avgHr,
                maxHr = maxOf(maxHr, stats?.max?.roundToInt() ?: 0),
                kcal = m.getData(DataType.CALORIES_TOTAL)?.total ?: s.kcal,
                steps = m.getData(DataType.STEPS_TOTAL)?.total ?: s.steps,
                distM = m.getData(DataType.DISTANCE_TOTAL)?.total ?: s.distM,
                zones = zones, msg = "",
            )
            if (st.isEnded) finish() else save(force = false)
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Workout.state.value = Workout.state.value.copy(msg = "Health Services: ${throwable.message?.take(60)}")
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    /** Mirror to kv at most every 15 s so a crash loses ≤ 15 s of zone time. */
    private fun save(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 15_000) return
        lastSave = now
        val s = Workout.state.value
        Store.kvSet("wo_live", JSONObject().put("type", s.type).put("program", s.programId).put("start", s.startMs).put("active_ms", s.elapsedMs(now))
            .put("avg", s.avgHr).put("max", s.maxHr).put("kcal", s.kcal).put("steps", s.steps).put("dist", s.distM)
            .put("zones", JSONArray(s.zones)).toString())
    }

    private fun restore() {
        if (Workout.state.value.active) return
        val j = runCatching { JSONObject(Store.kvGet("wo_live") ?: return) }.getOrNull() ?: return
        val z = j.optJSONArray("zones")
        val now = System.currentTimeMillis()
        Workout.state.value = Workout.Live(
            active = true, type = j.optString("type", "WORKOUT"), startMs = j.optLong("start", now), programId = j.optString("program", ""),
            activeMs = j.optLong("active_ms"), checkpointAt = now,
            avgHr = j.optInt("avg"), maxHr = j.optInt("max"), kcal = j.optDouble("kcal", 0.0),
            steps = j.optLong("steps"), distM = j.optDouble("dist", 0.0),
            zones = List(5) { z?.optLong(it) ?: 0L }, msg = "Reconnected",
        )
    }

    /** One `workouts` row + exercise minutes for the rings. Idempotent (id = device:workout:start). */
    private fun finish() {
        if (finished) return
        finished = true
        val s = Workout.state.value
        val end = System.currentTimeMillis()
        if (s.startMs > 0 && s.elapsedMs(end) >= 60_000) {
            val ex = Workout.extra
            Rec.workout(s.startMs, end, s.type, s.avgHr, s.maxHr, s.kcal, s.distM, s.steps,
                JSONObject().apply { s.zones.forEachIndexed { i, v -> put("z${i + 1}", v) } }
                    .put("active_s", s.elapsedMs(end) / 1000),
                programId = s.programId.ifEmpty { null }, sets = ex?.optJSONArray("sets"), bodySplit = ex?.optJSONObject("body_split"))
            runCatching { Day.markExerciseRange(s.startMs, end) }
        }
        Store.kvDel("wo_live")
        Workout.extra = null
        Workout.state.value = s.copy(active = false, paused = false, summary = true, activeMs = s.elapsedMs(end), checkpointAt = 0)
        runCatching { RingsTile.refresh(this) }
        client.clearUpdateCallbackAsync(callback)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Workout", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra("route", "workout"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle("Workout")
            .setContentText(Workout.pretty(Workout.state.value.type.ifEmpty { "WORKOUT" }))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // Shows a workout icon on the watch face; tapping it returns to the live screen.
        runCatching {
            OngoingActivity.Builder(applicationContext, NOTIF_ID, nb)
                .setStaticIcon(R.drawable.ic_workout)
                .setTouchIntent(open)
                .build().apply(applicationContext)
        }
        ServiceCompat.startForeground(this, NOTIF_ID, nb.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
