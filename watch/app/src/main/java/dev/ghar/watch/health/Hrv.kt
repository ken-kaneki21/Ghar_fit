package dev.ghar.watch.health

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 60-second HRV reading (RMSSD) from beat-to-beat intervals, via the Samsung Health Sensor SDK
 * (HEART_RATE_CONTINUOUS gives HR + IBI; verified on this watch in Sprint 0: RMSSD 29.6 ms).
 *
 * Clean-up: only IBIs the sensor marks good (status 0) and 300–2000 ms; a beat that differs > 20 % from the
 * previous one is treated as an artefact. Needs ≥ 30 clean intervals, otherwise "retry".
 */
object Hrv {
    data class Result(val rmssd: Double, val hr: Int, val beats: Int, val ok: Boolean, val error: String = "")

    private const val MIN_CLEAN = 30

    suspend fun measure(ctx: Context, seconds: Int = 60, progress: (sec: Int, hr: Int, beats: Int) -> Unit): Result {
        val svc = connect(ctx) ?: return Result(0.0, 0, 0, false, "Sensor service unavailable (dev mode on? restart watch)")
        val ibis = ArrayList<Int>()
        val hrs = ArrayList<Int>()
        var err = ""
        val tracker: HealthTracker = try {
            svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        } catch (e: Exception) {
            runCatching { svc.disconnectService() }
            return Result(0.0, 0, 0, false, "Tracker: ${e.message?.take(60)}")
        }
        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(list: List<DataPoint>) {
                synchronized(ibis) {
                    for (p in list) {
                        val st = runCatching { p.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }.getOrNull()
                        val h = runCatching { p.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()
                        if (st == 1 && h != null && h in 30..220) hrs.add(h)
                        val ib = runCatching { p.getValue(ValueKey.HeartRateSet.IBI_LIST) }.getOrNull() ?: emptyList()
                        val ibs = runCatching { p.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) }.getOrNull() ?: emptyList()
                        ib.forEachIndexed { i, v -> if ((ibs.getOrNull(i) ?: 1) == 0 && v in 300..2000) ibis.add(v) }
                    }
                }
            }
            override fun onFlushCompleted() {}
            override fun onError(error: HealthTracker.TrackerError) { err = error.name }
        }
        try {
            tracker.setEventListener(listener)
            for (s in 1..seconds) {
                delay(1_000)
                val (hr, n) = synchronized(ibis) { (hrs.lastOrNull() ?: 0) to ibis.size }
                progress(s, hr, n)
            }
        } finally {
            runCatching { tracker.unsetEventListener() }
            runCatching { svc.disconnectService() }
        }
        val (clean, hr) = synchronized(ibis) { cleanIbis(ibis) to (if (hrs.isNotEmpty()) hrs.sorted()[hrs.size / 2] else 0) }
        if (clean.size < MIN_CLEAN) return Result(0.0, hr, clean.size, false,
            if (err.isNotEmpty()) "Sensor: $err" else "Only ${clean.size} clean beats – sit still, strap snug, retry")
        val diffs = clean.zipWithNext { a, b -> (b - a).toDouble() }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        return Result((rmssd * 10).roundToInt() / 10.0, hr, clean.size, true)
    }

    /** Drop beats that jump > 20 % from the last accepted beat (missed / extra beats). */
    private fun cleanIbis(raw: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (v in raw) {
            val prev = out.lastOrNull()
            if (prev == null || abs(v - prev) <= prev * 0.2) out.add(v)
        }
        return out
    }

    /** Saves today's reading: daily_summary.hrv + one `hrv_rmssd` sample (with HR + beats) + local history. */
    fun save(r: Result) {
        val now = System.currentTimeMillis()
        val d = Rec.today()
        Rec.sample("hrv_rmssd", now, r.rmssd, JSONObject().put("hr", r.hr).put("beats", r.beats).put("secs", 60))
        Rec.daily(d, "hrv", r.rmssd)
        val hist = runCatching { JSONObject(Store.kvGet("hrv_hist") ?: "{}") }.getOrDefault(JSONObject())
        hist.put(d.toString(), r.rmssd)
        val keep = hist.keys().asSequence().toList().sorted().takeLast(30)
        val trimmed = JSONObject().apply { keep.forEach { put(it, hist.getDouble(it)) } }
        Store.kvSet("hrv_hist", trimmed.toString())
        // Baseline uses MORNING readings only (04:00–11:59), so post-walk / evening tests don't skew it.
        if (java.time.LocalTime.now().hour in 4..11) {
            val am = runCatching { JSONObject(Store.kvGet("hrv_am") ?: "{}") }.getOrDefault(JSONObject())
            am.put(d.toString(), r.rmssd)
            val k = am.keys().asSequence().toList().sorted().takeLast(30)
            Store.kvSet("hrv_am", JSONObject().apply { k.forEach { put(it, am.getDouble(it)) } }.toString())
        }
    }

    fun today(): Double? = runCatching { JSONObject(Store.kvGet("hrv_hist") ?: "{}").optDouble(Rec.today().toString()) }
        .getOrNull()?.takeIf { !it.isNaN() }

    /** Average of the previous 7 MORNING readings (excluding today), null until 3 exist. */
    fun baseline(): Double? {
        val hist = runCatching { JSONObject(Store.kvGet("hrv_am") ?: "{}") }.getOrNull() ?: return null
        val t = Rec.today().toString()
        val vals = hist.keys().asSequence().filter { it != t }.toList().sorted().takeLast(7).map { hist.getDouble(it) }
        return if (vals.size >= 3) vals.average() else null
    }

    private suspend fun connect(ctx: Context): HealthTrackingService? = withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { cont ->
            var svc: HealthTrackingService? = null
            val l = object : ConnectionListener {
                override fun onConnectionSuccess() { if (cont.isActive) cont.resume(svc) }
                override fun onConnectionEnded() {}
                override fun onConnectionFailed(e: HealthTrackerException) { if (cont.isActive) cont.resume(null) }
            }
            svc = HealthTrackingService(l, ctx.applicationContext)
            svc!!.connectService()
        }
    }
}
