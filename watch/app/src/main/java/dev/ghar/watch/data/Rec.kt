package dev.ghar.watch.data

import android.os.Build
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/** Builds server rows and puts them in the outbox. `person` is (re)filled at upload time. */
object Rec {
    const val APP_VERSION = "1.4.2"
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun today(): LocalDate = LocalDate.now(zone)

    /** One sample row. Same (type, ts) → same id → upsert, never a duplicate. */
    fun sample(type: String, tsMs: Long, value: Double, meta: JSONObject? = null) {
        val id = "${Prefs.deviceId}:$type:$tsMs"
        val o = JSONObject()
            .put("id", id).put("person", Prefs.person).put("device_id", Prefs.deviceId)
            .put("ts", iso(tsMs)).put("type", type).put("value", value)
        if (meta != null) o.put("meta", meta)
        Store.enqueue("samples", id, o)
    }

    /** Cumulative daily value snapshot, one row per 15-minute bucket (curve for charts). */
    fun cumulative(type: String, tsMs: Long, value: Double) {
        val bucket = tsMs - (tsMs % (15 * 60_000L))
        sample(type, bucket, value)
    }

    private val DAILY_FIELDS = listOf("steps", "floors", "distance_m", "sleep_min", "exercise_min", "active_hours", "water_ml")

    /** Counters that only grow during a day. Health Services may restart them from 0 when tracking is re-registered. */
    private val MONOTONIC = setOf("steps", "kcal_total", "floors", "distance_m")

    /**
     * Updates one field of a day's summary and re-queues the whole row. Returns the stored value.
     * For counters: if the raw value drops (tracking re-registered → counter restarted), the previous raw
     * total is added to a per-day base, so the day's total never goes backwards and no steps are lost.
     */
    fun daily(date: LocalDate, field: String, value: Double): Double {
        var v = value
        if (field in MONOTONIC) {
            val rawKey = "day:$date:$field:raw"
            val baseKey = "day:$date:$field:base"
            val lastRaw = Store.kvGet(rawKey)?.toDoubleOrNull()
            var base = Store.kvGet(baseKey)?.toDoubleOrNull() ?: 0.0
            if (lastRaw != null && value + 0.5 < lastRaw) {
                base += lastRaw
                Store.kvSet(baseKey, base.toString())
            }
            Store.kvSet(rawKey, value.toString())
            v = base + value
        }
        Store.kvSet("day:$date:$field", v.toString())
        val o = JSONObject().put("person", Prefs.person).put("date", date.toString())
        DAILY_FIELDS.forEach { f -> Store.kvGet("day:$date:$f")?.toDoubleOrNull()?.let { o.put(f, it.roundToLong()) } }
        // Local key "kcal_total" = what the watch reports (active kcal on the SM-R870). Server gets both, correctly named.
        Store.kvGet("day:$date:kcal_total")?.toDoubleOrNull()?.let { raw ->
            val (active, total) = kcal(date, raw)
            o.put("kcal_active", active.roundToLong()).put("kcal_total", total.roundToLong())
        }
        Store.kvGet("day:$date:hrv")?.toDoubleOrNull()?.let { o.put("hrv", Math.round(it * 10) / 10.0) }
        o.put("hourly", Day.hourlyJson(date))
        Store.enqueue("daily_summary", date.toString(), o)
        return v
    }

    /**
     * (active, total) kcal. Resting burn = Mifflin BMR × fraction of the day elapsed (whole day for past dates).
     * If the watch value is far below resting burn it is already ACTIVE kcal (seen on SM-R870); otherwise it is total.
     */
    fun kcal(date: LocalDate, raw: Double): Pair<Double, Double> {
        val frac = if (date == today()) java.time.LocalTime.now().toSecondOfDay() / 86_400.0 else 1.0
        val rest = Prefs.bmr() * frac
        return if (raw < rest * 0.5) raw to raw + rest else (raw - rest).coerceAtLeast(0.0) to raw
    }

    /** Body measurement entry (weekly). Navy estimate computed on the watch, Samsung BIA values typed in. */
    fun bodyComp(date: LocalDate, json: JSONObject) {
        val id = "${Prefs.person}:body_comp:$date"
        val o = JSONObject().put("id", id).put("person", Prefs.person).put("date", date.toString())
            .put("kind", "body_comp").put("json", json)
        Store.enqueue("labels", id, o)
    }

    fun dailyValue(date: LocalDate, field: String): Double? = Store.kvGet("day:$date:$field")?.toDoubleOrNull()

    /** Sleep session row. endMs = null while still asleep (saved every 30 min, so a night is never lost). */
    fun sleep(startMs: Long, endMs: Long?) {
        val id = "${Prefs.deviceId}:sleep:$startMs"
        val end = endMs ?: System.currentTimeMillis()
        val o = JSONObject()
            .put("id", id).put("person", Prefs.person).put("device_id", Prefs.deviceId)
            .put("start_ts", iso(startMs))
            .put("end_ts", if (endMs != null) iso(endMs) else JSONObject.NULL)
            .put("total_min", ((end - startMs) / 60_000L).toInt())
        Store.enqueue("sleep_sessions", id, o)
    }

    fun checkin(date: LocalDate, bedMs: Long, wakeMs: Long, rested: Int) {
        val id = "${Prefs.person}:checkin:$date"
        val o = JSONObject()
            .put("id", id).put("person", Prefs.person).put("date", date.toString()).put("kind", "checkin")
            .put("json", JSONObject().put("bed_ts", iso(bedMs)).put("wake_ts", iso(wakeMs)).put("rested", rested)
                .put("detected", Store.kvGet("last_sleep") ?: JSONObject.NULL))
        Store.enqueue("labels", id, o)
        Store.kvSet("checkin_done", date.toString())
    }

    fun workout(startMs: Long, endMs: Long, type: String, avgHr: Int, maxHr: Int, kcal: Double, distM: Double, steps: Long, zones: JSONObject) {
        val id = "${Prefs.deviceId}:workout:$startMs"
        val o = JSONObject()
            .put("id", id).put("person", Prefs.person).put("device_id", Prefs.deviceId)
            .put("start_ts", iso(startMs)).put("end_ts", iso(endMs)).put("exercise_type", type)
            .put("kcal", kcal.roundToLong()).put("distance_m", distM.roundToLong()).put("steps", steps)
            .put("zones", zones)
        if (avgHr > 0) o.put("avg_hr", avgHr)
        if (maxHr > 0) o.put("max_hr", maxHr)
        Store.enqueue("workouts", id, o)
    }

    fun device(battery: Int?) {
        val o = JSONObject()
            .put("device_id", Prefs.deviceId).put("person", Prefs.person)
            .put("model", Build.MODEL).put("os_build", Build.DISPLAY).put("app_version", APP_VERSION)
            .put("last_upload", iso(System.currentTimeMillis()))
        if (battery != null) o.put("battery", battery)
        Store.enqueue("devices", Prefs.deviceId, o)
    }
}
