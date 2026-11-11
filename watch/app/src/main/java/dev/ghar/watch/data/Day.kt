package dev.ghar.watch.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Derived daily metrics, all kept in the kv table (no DB migration):
 *  - hourly arrays (24 values):  h:<date>:<name>   names: steps, kcal, exmin, hrsum, hrn, hrmin, hrmax
 *  - exercise minutes bitset:    ex:<date>         1440 chars '0'/'1'
 *  - last cumulative reading:    c:<date>:<field>  "endMs|total"
 *  - HR minute accumulator:      hr_acc            {minuteMs: [sum, n, min, max]}
 *
 * Exercise minute  = HR minute avg ≥ 60 % of max HR, OR step cadence ≥ 100/min.
 * Active hour      = an hour with ≥ 250 steps.
 */
object Day {
    const val ACTIVE_HOUR_STEPS = 250.0
    const val BRISK_CADENCE = 100.0
    private const val MIN = 60_000L

    fun hrExerciseBpm(): Double = 0.6 * (208.0 - 0.7 * Prefs.age)      // Tanaka max HR; ≈115 bpm at 24

    // ---------------- hourly arrays ----------------
    fun hourly(date: LocalDate, name: String): DoubleArray {
        val a = DoubleArray(24)
        Store.kvGet("h:$date:$name")?.split(",")?.forEachIndexed { i, s -> if (i < 24) a[i] = s.toDoubleOrNull() ?: 0.0 }
        return a
    }

    private fun saveHourly(date: LocalDate, name: String, a: DoubleArray) =
        Store.kvSet("h:$date:$name", a.joinToString(",") { if (it == it.toLong().toDouble()) it.toLong().toString() else String.format(java.util.Locale.US, "%.1f", it) })   // US: never a decimal comma inside a CSV

    private fun hourOf(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(Rec.zone).hour
    private fun nextLocalHour(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(Rec.zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
    private fun dayStart(date: LocalDate): Long = date.atStartOfDay(Rec.zone).toInstant().toEpochMilli()

    /**
     * A new cumulative daily total arrived (already reset-safe). The increase since the last reading is
     * spread over the hours it covers → hourly steps/kcal bars, active hours, brisk-walking minutes.
     */
    @Synchronized
    fun onCumulative(field: String, date: LocalDate, endMs: Long, total: Double) {
        val name = when (field) { "steps" -> "steps"; "kcal_total" -> "kcal"; else -> return }
        val key = "c:$date:$field"
        val prev = Store.kvGet(key)?.split("|")
        val prevMs = prev?.getOrNull(0)?.toLongOrNull()
        val prevTotal = prev?.getOrNull(1)?.toDoubleOrNull()
        Store.kvSet(key, "$endMs|$total")
        val delta = if (prevTotal == null) total else total - prevTotal
        if (delta <= 0.0) return
        val start = (prevMs ?: endMs).coerceIn(dayStart(date), endMs)

        val a = hourly(date, name)
        if (endMs - start < MIN) {
            a[hourOf(endMs)] += delta
        } else {
            var t = start
            while (t < endMs) {
                val next = minOf(endMs, nextLocalHour(t))          // local hours (IST is +5:30, not whole epoch hours)
                a[hourOf(t)] += delta * (next - t) / (endMs - start)
                t = next
            }
        }
        saveHourly(date, name, a)

        if (name == "steps") {
            val mins = (endMs - start) / MIN
            if (prevMs != null && mins in 1L..20L && delta / mins >= BRISK_CADENCE) markExercise(date, start, endMs)
            Rec.daily(date, "active_hours", activeHours(date).toDouble())
        }
    }

    // ---------------- exercise minutes ----------------
    @Synchronized
    private fun markExercise(date: LocalDate, fromMs: Long, toMs: Long) {
        val key = "ex:$date"
        val bits = (Store.kvGet(key) ?: "0".repeat(1440)).toCharArray()
        val d0 = dayStart(date)
        val a = ((fromMs - d0) / MIN).toInt().coerceIn(0, 1439)
        val b = ((toMs - 1 - d0) / MIN).toInt().coerceIn(0, 1439)
        var changed = false
        for (i in a..b) if (bits[i] != '1') { bits[i] = '1'; changed = true }
        if (!changed) return
        Store.kvSet(key, String(bits))
        val ex = hourly(date, "exmin")
        for (h in 0 until 24) ex[h] = (h * 60 until h * 60 + 60).count { bits[it] == '1' }.toDouble()
        saveHourly(date, "exmin", ex)
        Rec.daily(date, "exercise_min", exerciseMinutes(date).toDouble())
    }

    /** A recorded workout counts fully toward exercise minutes (like Apple's Exercise ring). */
    fun markExerciseRange(fromMs: Long, toMs: Long) {
        var d = Rec.dateOf(fromMs)
        while (!d.isAfter(Rec.dateOf(toMs))) {
            val a = maxOf(fromMs, dayStart(d)); val b = minOf(toMs, dayStart(d.plusDays(1)))
            if (b > a) markExercise(d, a, b)
            d = d.plusDays(1)
        }
    }

    fun exerciseMinutes(date: LocalDate): Int = Store.kvGet("ex:$date")?.count { it == '1' } ?: 0
    fun activeHours(date: LocalDate): Int = hourly(date, "steps").count { it >= ACTIVE_HOUR_STEPS }

    // ---------------- heart rate: 1 row per minute instead of 1 per second ----------------
    /** points: (epochMs, bpm). Writes/updates one `hr_1m` sample per minute: value = avg, meta = min/max/n. */
    @Synchronized
    fun onHeartRate(points: List<Pair<Long, Double>>) {
        if (points.isEmpty()) return
        val closedUpTo = Store.kvGet("hr_closed")?.toLongOrNull() ?: 0L
        val acc = runCatching { JSONObject(Store.kvGet("hr_acc") ?: "{}") }.getOrDefault(JSONObject())
        val touched = HashSet<Long>()
        for ((ms, v) in points) {
            val m = ms - Math.floorMod(ms, MIN)
            if (m <= closedUpTo) continue                       // late point for a finished minute: ignore
            val k = m.toString()
            val s = acc.optJSONArray(k)
            if (s == null) acc.put(k, JSONArray().put(v).put(1).put(v).put(v))
            else { s.put(0, s.getDouble(0) + v); s.put(1, s.getInt(1) + 1); s.put(2, minOf(s.getDouble(2), v)); s.put(3, maxOf(s.getDouble(3), v)) }
            touched.add(m)
        }
        for (m in touched) writeMinute(m, acc.getJSONArray(m.toString()))

        // Every minute older than the newest one is complete → feed hourly HR + exercise, drop from acc.
        val newest = acc.keys().asSequence().mapNotNull { it.toLongOrNull() }.maxOrNull() ?: return
        var closed = closedUpTo
        for (k in acc.keys().asSequence().toList()) {
            val m = k.toLongOrNull() ?: continue
            if (m < newest) {
                val s = acc.getJSONArray(k)
                closeMinute(m, s.getDouble(0) / s.getInt(1), s.getDouble(2), s.getDouble(3))
                acc.remove(k); closed = maxOf(closed, m)
            }
        }
        Store.kvSet("hr_acc", acc.toString())
        Store.kvSet("hr_closed", closed.toString())
    }

    private fun writeMinute(m: Long, s: JSONArray) {
        val n = s.getInt(1)
        val avg = s.getDouble(0) / n
        Rec.sample("hr_1m", m, (avg * 10).roundToInt() / 10.0,
            JSONObject().put("min", s.getDouble(2).roundToInt()).put("max", s.getDouble(3).roundToInt()).put("n", n))
    }

    private fun closeMinute(m: Long, avg: Double, mn: Double, mx: Double) {
        val date = Rec.dateOf(m)
        val h = hourOf(m)
        val sum = hourly(date, "hrsum"); val n = hourly(date, "hrn")
        val lo = hourly(date, "hrmin"); val hi = hourly(date, "hrmax")
        sum[h] += avg; n[h] += 1.0
        lo[h] = if (lo[h] == 0.0) mn else minOf(lo[h], mn)
        hi[h] = maxOf(hi[h], mx)
        saveHourly(date, "hrsum", sum); saveHourly(date, "hrn", n); saveHourly(date, "hrmin", lo); saveHourly(date, "hrmax", hi)
        val line = hrLine(date)
        val idx = ((m - dayStart(date)) / MIN).toInt()
        if (idx in 0..1439) { line[idx] = avg.roundToInt(); Store.kvSet("hrl:$date", line.joinToString(",") { if (it > 0) it.toString() else "" }) }
        if (avg >= hrExerciseBpm()) markExercise(date, m, m + MIN)
    }

    /** Today's per-minute HR (1440 slots, 0 = no reading). Kept locally for the line chart. */
    fun hrLine(date: LocalDate): IntArray {
        val a = IntArray(1440)
        Store.kvGet("hrl:$date")?.split(",")?.forEachIndexed { i, v -> if (i < 1440) a[i] = v.toIntOrNull() ?: 0 }
        return a
    }

    /** 5-minute averages for drawing (288 slots, 0 = gap). */
    fun hrLine5(date: LocalDate): List<Int> {
        val a = hrLine(date)
        return List(288) { b ->
            var sum = 0; var n = 0
            for (i in b * 5 until b * 5 + 5) if (a[i] > 0) { sum += a[i]; n++ }
            if (n > 0) (sum + n / 2) / n else 0
        }
    }

    fun hrMaxEst(): Double = 208.0 - 0.7 * Prefs.age

    /** Today's HR: min, avg, max (null if no data). */
    fun hrRange(date: LocalDate): Triple<Int, Int, Int>? {
        val n = hourly(date, "hrn"); val total = n.sum()
        if (total < 1) return null
        val avg = hourly(date, "hrsum").sum() / total
        val lo = hourly(date, "hrmin").filter { it > 0 }.minOrNull() ?: return null
        val hi = hourly(date, "hrmax").maxOrNull() ?: return null
        return Triple(lo.roundToInt(), avg.roundToInt(), hi.roundToInt())
    }

    fun hourlyHrAvg(date: LocalDate): DoubleArray {
        val s = hourly(date, "hrsum"); val n = hourly(date, "hrn")
        return DoubleArray(24) { if (n[it] > 0) s[it] / n[it] else 0.0 }
    }

    // ---------------- water ----------------
    @Synchronized
    fun addWater(ml: Int) {
        val d = Rec.today()
        val log = Store.kvGet("water_log:$d")?.split(",")?.mapNotNull { it.toIntOrNull() }?.toMutableList() ?: mutableListOf()
        if (ml > 0) log.add(ml) else if (log.isNotEmpty()) log.removeAt(log.size - 1) else return
        Store.kvSet("water_log:$d", log.joinToString(","))
        Store.kvSet("water_last", System.currentTimeMillis().toString())
        Rec.daily(d, "water_ml", log.sum().toDouble())
    }

    fun waterMl(date: LocalDate): Int = (Rec.dailyValue(date, "water_ml") ?: 0.0).toInt()

    /** For the server row: compact hourly curves for the iPhone app. */
    fun hourlyJson(date: LocalDate): JSONObject {
        fun arr(a: DoubleArray) = JSONArray().apply { a.forEach { put(it.roundToInt()) } }
        return JSONObject()
            .put("steps", arr(hourly(date, "steps")))
            .put("kcal", arr(hourly(date, "kcal")))
            .put("exmin", arr(hourly(date, "exmin")))
            .put("hr_avg", arr(hourlyHrAvg(date)))
    }
}
