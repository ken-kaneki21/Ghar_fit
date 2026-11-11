package dev.ghar.watch.complication

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.ghar.watch.R
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store

/**
 * Watch-face complications (works on any face with third-party complication slots).
 * Move (active kcal vs goal), Steps (vs goal), Heart (last bpm). Ranged = progress arc, short text = number.
 * Refreshed with the tile (≤ every 5 min on new data) + the system's 5-min update period.
 */
object Comp {
    fun refresh(ctx: Context) {
        for (c in listOf(MoveComplication::class.java, StepsComplication::class.java, HeartComplication::class.java)) {
            runCatching { ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, c)).requestUpdateAll() }
        }
    }

    private fun t(s: String) = PlainComplicationText.Builder(s).build()
    private fun icon(ctx: Context, res: Int) = MonochromaticImage.Builder(Icon.createWithResource(ctx, res)).build()

    fun build(ctx: Context, type: ComplicationType, value: Float, max: Float, text: String, title: String, res: Int, desc: String): ComplicationData? =
        when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(value.coerceIn(0f, max), 0f, max, t(desc))
                .setText(t(text)).setTitle(t(title)).setMonochromaticImage(icon(ctx, res)).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(t(text), t(desc))
                .setTitle(t(title)).setMonochromaticImage(icon(ctx, res)).build()
            else -> null
        }

    fun steps(ctx: Context): Long { Store.init(ctx); return (Rec.dailyValue(Rec.today(), "steps") ?: 0.0).toLong() }
    fun move(ctx: Context): Long { Store.init(ctx); val d = Rec.today(); return Rec.kcal(d, Rec.dailyValue(d, "kcal_total") ?: 0.0).first.toLong() }
    fun hr(ctx: Context): Int? { Store.init(ctx); return Store.kvGet("last_hr")?.substringBefore("@")?.toIntOrNull() }
    fun short(n: Long) = if (n >= 10_000) "%.1fk".format(n / 1000.0) else n.toString()
}

class MoveComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType) = Comp.build(this, type, 320f, 600f, "320", "MOVE", R.drawable.ic_move, "Move 320 of 600 kcal")
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val v = Comp.move(this); val g = Prefs.goalMove
        return Comp.build(this, request.complicationType, v.toFloat(), g.toFloat(), v.toString(), "MOVE", R.drawable.ic_move, "Move $v of $g kcal")
    }
}

class StepsComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType) = Comp.build(this, type, 6400f, 10000f, "6400", "STEPS", R.drawable.ic_steps, "6400 of 10000 steps")
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val v = Comp.steps(this); val g = Prefs.goalSteps
        return Comp.build(this, request.complicationType, v.toFloat(), g.toFloat(), Comp.short(v), "STEPS", R.drawable.ic_steps, "$v of $g steps")
    }
}

class HeartComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType) = Comp.build(this, type, 72f, 200f, "72", "BPM", R.drawable.ic_heart, "Heart rate 72")
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val v = Comp.hr(this)
        return Comp.build(this, request.complicationType, (v ?: 0).toFloat(), 200f, v?.toString() ?: "–", "BPM", R.drawable.ic_heart, "Heart rate ${v ?: "unknown"}")
    }
}
