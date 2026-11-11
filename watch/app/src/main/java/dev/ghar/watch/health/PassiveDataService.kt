package dev.ghar.watch.health

import android.os.SystemClock
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import java.time.Instant
import kotlin.math.roundToInt

/** Health Services pushes batched data here even while the app is closed. Writes go straight to SQLite. */
class PassiveDataService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        Store.init(applicationContext)
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

        // HR arrives ~1/s with continuous measuring → aggregate to 1 row per minute (avg/min/max/n).
        val hr = dataPoints.getData(DataType.HEART_RATE_BPM)
            .map { it.getTimeInstant(boot).toEpochMilli() to it.value }
            .filter { it.second in 25.0..230.0 }
            .sortedBy { it.first }
        if (hr.isNotEmpty()) {
            Day.onHeartRate(hr)
            val (ms, v) = hr.last()
            Store.kvSet("last_hr", "${v.roundToInt()}@$ms")
        }
        daily(dataPoints.getData(DataType.STEPS_DAILY), boot, "steps", "steps_cum")
        daily(dataPoints.getData(DataType.CALORIES_DAILY), boot, "kcal_total", "kcal_cum")
        daily(dataPoints.getData(DataType.FLOORS_DAILY), boot, "floors", null)
        daily(dataPoints.getData(DataType.DISTANCE_DAILY), boot, "distance_m", null)
        Store.kvSet("last_data", System.currentTimeMillis().toString())
        runCatching { dev.ghar.watch.tile.RingsTile.refresh(applicationContext, force = false) }
    }

    /** Daily totals: keep the latest point PER DATE, so a batch that spans midnight keeps yesterday's final value. */
    private fun <T : Number> daily(points: List<IntervalDataPoint<T>>, boot: Instant, field: String, cumType: String?) {
        points.groupBy { Rec.dateOf(it.getStartInstant(boot).toEpochMilli()) }.forEach { (date, ps) ->
            val p = ps.maxBy { it.getEndInstant(boot) }
            val stored = Rec.daily(date, field, p.value.toDouble())
            val endMs = p.getEndInstant(boot).toEpochMilli()
            if (cumType != null) Rec.cumulative(cumType, endMs, stored)
            Day.onCumulative(field, date, endMs, stored)
        }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        Store.init(applicationContext)
        Sleep.onState(info.userActivityState, info.stateChangeTime.toEpochMilli())
    }

    override fun onPermissionLost() {
        Store.init(applicationContext)
        Store.kvSet("passive_status", "PERMISSION LOST – open Ghar Watch")
    }
}

/** Sleep timing from Health Services' asleep state. Sprint 3 adds actigraphy + HRV on top. */
object Sleep {
    private const val MIN_SLEEP_MS = 20 * 60_000L

    fun onState(state: UserActivityState, ms: Long) {
        val code = when (state) {
            UserActivityState.USER_ACTIVITY_PASSIVE -> 0.0
            UserActivityState.USER_ACTIVITY_EXERCISE -> 1.0
            UserActivityState.USER_ACTIVITY_ASLEEP -> 2.0
            else -> -1.0
        }
        Rec.sample("activity_state", ms, code)

        val start = Store.kvGet("sleep_start")?.toLongOrNull()
        if (state == UserActivityState.USER_ACTIVITY_ASLEEP) {
            if (start == null) {
                Store.kvSet("sleep_start", ms.toString())
                Rec.sleep(ms, null)
            }
        } else if (start != null && ms > start) {
            Store.kvDel("sleep_start")
            Rec.sleep(start, ms)
            if (ms - start >= MIN_SLEEP_MS) {
                Store.kvSet("last_sleep", "$start-$ms")
                val wakeDate = Rec.dateOf(ms)
                val mins = ((ms - start) / 60_000L).toDouble()
                val prev = Rec.dailyValue(wakeDate, "sleep_min") ?: 0.0
                Rec.daily(wakeDate, "sleep_min", maxOf(prev, mins))
                Recovery.recordNight(start, ms)
            }
        }
    }

    /** Called every 30 min by the heartbeat: keeps the open session saved so a night is never lost. */
    fun checkpoint() {
        Store.kvGet("sleep_start")?.toLongOrNull()?.let { Rec.sleep(it, null) }
        Recovery.update()
    }
}
