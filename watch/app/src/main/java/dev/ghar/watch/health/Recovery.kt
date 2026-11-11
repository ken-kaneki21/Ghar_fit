package dev.ghar.watch.health

import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * v1.7 recovery: sleep history, resting HR, readiness (energy) score.
 * Everything is computed on the watch from data it already keeps; nothing new is measured.
 *
 *  - Sleep night for a date = the main sleep that ENDED that day (check-in edit wins over detection).
 *  - Resting HR = lowest 15-min average heart rate inside that sleep (≥ 10 valid minutes);
 *    without a detected sleep, 00:00–07:00 is used.
 *  - Readiness 0–100 = weighted mix of what exists: HRV vs 7-day baseline (35), resting HR vs 7-day (25),
 *    sleep hours (30), yesterday's exercise load (10). Missing parts are left out and weights re-scaled.
 */
object Recovery {
    data class Night(val start: Long, val end: Long) { val hours get() = (end - start) / 3_600_000.0 }
    data class Part(val name: String, val score: Int, val weight: Int, val detail: String)
    data class Ready(val score: Int, val label: String, val parts: List<Part>)

    private const val HIST = "sleep_hist"     // JSON: date → "start-end"

    /** Called when a sleep ends (detected) or a check-in is saved (user-corrected). */
    fun recordNight(start: Long, end: Long, userSet: Boolean = false) {
        if (end <= start) return
        val date = Rec.dateOf(end).toString()
        val h = hist()
        val prev = h.optString(date, "").split("-").mapNotNull { it.toLongOrNull() }
        // a check-in always wins; otherwise keep the longest detected sleep of the day
        if (!userSet && Store.kvGet("sleep_user:$date") != null) return
        if (!userSet && prev.size == 2 && prev[1] - prev[0] >= end - start) return
        h.put(date, "$start-$end")
        if (userSet) Store.kvSet("sleep_user:$date", "1")
        val keep = h.keys().asSequence().toList().sorted().takeLast(30)
        Store.kvSet(HIST, JSONObject().apply { keep.forEach { put(it, h.getString(it)) } }.toString())
        update(Rec.dateOf(end))
    }

    private fun hist(): JSONObject = runCatching { JSONObject(Store.kvGet(HIST) ?: "{}") }.getOrDefault(JSONObject())

    fun night(date: LocalDate): Night? {
        val p = hist().optString(date.toString(), "").split("-").mapNotNull { it.toLongOrNull() }
        return if (p.size == 2) Night(p[0], p[1]) else null
    }

    /** Last n nights, oldest first (null where nothing was recorded). */
    fun nights(n: Int, upTo: LocalDate = Rec.today()): List<Pair<LocalDate, Night?>> =
        (n - 1 downTo 0).map { upTo.minusDays(it.toLong()) }.map { it to night(it) }

    /** Minute-by-minute HR between two instants (crosses midnight). 0 = no reading. */
    fun hrBetween(start: Long, end: Long): IntArray {
        val out = ArrayList<Int>()
        var t = start - Math.floorMod(start, 60_000L)
        var cacheDate: LocalDate? = null; var line = IntArray(0)
        while (t < end && out.size < 24 * 60) {
            val d = Rec.dateOf(t)
            if (d != cacheDate) { cacheDate = d; line = Day.hrLine(d) }
            val z = java.time.Instant.ofEpochMilli(t).atZone(Rec.zone)
            out += line.getOrElse(z.hour * 60 + z.minute) { 0 }
            t += 60_000L
        }
        return out.toIntArray()
    }

    fun restingHr(date: LocalDate): Int? {
        val n = night(date)
        val (s, e) = n?.let { it.start to it.end }
            ?: (date.atStartOfDay(Rec.zone).toInstant().toEpochMilli() to date.atTime(7, 0).atZone(Rec.zone).toInstant().toEpochMilli())
        if (date == Rec.today() && n == null && java.time.LocalTime.now().hour < 7) return null   // window not finished
        val a = hrBetween(s, e)
        var best = Double.MAX_VALUE
        for (i in 0..(a.size - 15).coerceAtLeast(-1)) {
            var sum = 0; var c = 0
            for (j in i until minOf(i + 15, a.size)) if (a[j] in 30..140) { sum += a[j]; c++ }
            if (c >= 10) best = minOf(best, sum.toDouble() / c)
        }
        return if (best == Double.MAX_VALUE) null else best.roundToInt()
    }

    /** Resting HR baseline: mean of the previous 7 days that have one (needs 3). */
    fun rhrBaseline(date: LocalDate): Double? {
        val v = (1..7).mapNotNull { Rec.dailyValue(date.minusDays(it.toLong()), "resting_hr") }
        return if (v.size >= 3) v.average() else null
    }

    fun readiness(date: LocalDate = Rec.today()): Ready? {
        val parts = mutableListOf<Part>()
        // HRV (only meaningful for today: the morning reading)
        val hrv = if (date == Rec.today()) Hrv.today() else Rec.dailyValue(date, "hrv")
        val hb = Hrv.baseline()
        if (hrv != null && hb != null && hb > 0) {
            val r = hrv / hb
            parts += Part("HRV", (50 + (r - 1) * 250).roundToInt().coerceIn(0, 100), 35,
                "%.0f ms · %+.0f%% vs 7-day".format(hrv, (r - 1) * 100))
        }
        val rhr = Rec.dailyValue(date, "resting_hr")?.roundToInt() ?: restingHr(date)
        val rb = rhrBaseline(date)
        if (rhr != null && rb != null) {
            val d = rhr - rb
            parts += Part("Resting HR", (60 - d * 8).roundToInt().coerceIn(0, 100), 25, "$rhr bpm · %+.0f vs 7-day".format(d))
        }
        night(date)?.let { n ->
            val h = n.hours
            val sc = when { h >= 7.5 -> 100.0; h <= 4.5 -> 15.0; else -> 15 + (h - 4.5) / 3.0 * 85 }
            parts += Part("Sleep", sc.roundToInt(), 30, "%.1f h".format(h))
        }
        val yEx = Day.exerciseMinutes(date.minusDays(1))
        val hasYesterday = Rec.dailyValue(date.minusDays(1), "steps") != null
        if (hasYesterday) {
            val sc = when { yEx >= 120 -> 35; yEx >= 75 -> 60; yEx >= 20 -> 85; else -> 95 }
            parts += Part("Yesterday's load", sc, 10, "$yEx exercise min")
        }
        // needs at least sleep or HRV to say anything useful
        if (parts.none { it.name == "Sleep" || it.name == "HRV" }) return null
        val w = parts.sumOf { it.weight }
        val score = (parts.sumOf { it.score * it.weight }.toDouble() / w).roundToInt()
        val label = when { score >= 75 -> "Go hard"; score >= 50 -> "Normal day"; else -> "Take it easy" }
        return Ready(score, label, parts)
    }

    /** Recompute resting HR + readiness for a date and save them only when they change (no upload spam). */
    fun update(date: LocalDate = Rec.today()) {
        runCatching {
            restingHr(date)?.let { v ->
                if (Rec.dailyValue(date, "resting_hr")?.roundToInt() != v) Rec.daily(date, "resting_hr", v.toDouble())
            }
            readiness(date)?.let { r ->
                if (Rec.dailyValue(date, "readiness")?.roundToInt() != r.score) Rec.daily(date, "readiness", r.score.toDouble())
            }
        }
    }
}
