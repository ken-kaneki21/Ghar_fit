package dev.ghar.watch.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.ghar.watch.data.Day
import dev.ghar.watch.data.Prefs
import dev.ghar.watch.data.Rec
import dev.ghar.watch.data.Store
import dev.ghar.watch.presentation.MainActivity

/**
 * Swipe-left Tile: the 3 rings + numbers, same colours as the app. Refreshes every 15 min and
 * whenever new data arrives (throttled to 5 min). Tap → opens Ghar Watch.
 */
class RingsTile : TileService() {
    companion object {
        private const val PX = 368
        private val MOVE = 0xFFFA114F.toInt()
        private val EX = 0xFF92E82A.toInt()
        private val HRS = 0xFF1EEAEF.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()
        private val MUTED = 0xFF8E8E93.toInt()

        /** Ask the system to redraw the tile; at most every 5 min unless forced. */
        fun refresh(ctx: Context, force: Boolean = true) {
            Store.init(ctx)
            val now = System.currentTimeMillis()
            val last = Store.kvGet("tile_req")?.toLongOrNull() ?: 0L
            if (!force && now - last < 5 * 60_000L) return
            Store.kvSet("tile_req", now.toString())
            runCatching { getUpdater(ctx).requestUpdate(RingsTile::class.java) }
            runCatching { dev.ghar.watch.complication.Comp.refresh(ctx) }
        }

        private fun dim(c: Int): Int {           // 22 % of the colour on black = ring track
            fun ch(shift: Int) = (((c shr shift) and 0xFF) * 0.22f).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    private data class Vals(val active: Long, val ex: Int, val hrs: Int, val steps: Long) {
        val version get() = "r-$active-$ex-$hrs-${Prefs.goalMove}-${Prefs.goalExercise}-${Prefs.goalHours}"
    }

    private fun read(): Vals {
        Store.init(this)
        val d = Rec.today()
        val (active, _) = Rec.kcal(d, Rec.dailyValue(d, "kcal_total") ?: 0.0)
        return Vals(active.toLong(), Day.exerciseMinutes(d), Day.activeHours(d), (Rec.dailyValue(d, "steps") ?: 0.0).toLong())
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val v = read()
        val root = Box.Builder().setWidth(expand()).setHeight(expand())
            // Rings are a pre-drawn image: Samsung's tile renderer recolours dark arc tracks, it can't touch a bitmap.
            .addContent(LayoutElementBuilders.Image.Builder().setResourceId("rings").setWidth(dp(184f)).setHeight(dp(184f)).build())
            .addContent(Column.Builder()
                .addContent(text("${v.active}/${Prefs.goalMove}", 13f, MOVE, true))
                .addContent(text("${v.ex}/${Prefs.goalExercise} min", 13f, EX, true))
                .addContent(text("${v.hrs}/${Prefs.goalHours} h", 13f, HRS, true))
                .addContent(text("%,d steps".format(v.steps), 11f, MUTED, false))
                .build())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(
                ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
                    ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    ).build()
                ).build()
            ).build())
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(v.version)          // new numbers → new version → host re-fetches the image
            .setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return done(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val v = read()
        val png = ringsPng(listOf(
            v.active.toFloat() / Prefs.goalMove to MOVE,
            v.ex.toFloat() / Prefs.goalExercise to EX,
            v.hrs.toFloat() / Prefs.goalHours to HRS,
        ))
        val img = ResourceBuilders.ImageResource.Builder().setInlineResource(
            ResourceBuilders.InlineImageResource.Builder()
                .setData(png).setWidthPx(PX).setHeightPx(PX)
                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)   // PNG bytes
                .build()
        ).build()
        return done(ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)
            .addIdToImageMapping("rings", img)
            .build())
    }

    /** Same look as the app: dark full track, bright round-capped progress from 12 o'clock. */
    private fun ringsPng(rings: List<Pair<Float, Int>>): ByteArray {
        val bmp = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = PX * 0.085f
        val gap = sw * 0.22f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw }
        rings.forEachIndexed { i, (p, col) ->
            val inset = sw / 2f + i * (sw + gap)
            val r = RectF(inset, inset, PX - inset, PX - inset)
            paint.color = track(col); paint.strokeCap = Paint.Cap.BUTT
            c.drawArc(r, 0f, 360f, false, paint)
            val sweep = p.coerceIn(0f, 1f) * 360f
            if (sweep > 0.5f) { paint.color = col; paint.strokeCap = Paint.Cap.ROUND; c.drawArc(r, -90f, sweep, false, paint) }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun track(c: Int): Int = when (c) {
        MOVE -> 0xFF3A0716.toInt()
        EX -> 0xFF22340A.toInt()
        HRS -> 0xFF073638.toInt()
        else -> dim(c)
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean) = Text.Builder()
        .setText(t)
        .setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build())
        .build()

    private fun <T> done(v: T): ListenableFuture<T> = ResolvableFuture.create<T>().also { it.set(v) }
}
