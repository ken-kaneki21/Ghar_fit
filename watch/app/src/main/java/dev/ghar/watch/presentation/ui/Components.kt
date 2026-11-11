package dev.ghar.watch.presentation.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

/** Standard screen: time on top, round-aware scaling list, scroll indicator, bezel scrolling built in. */
@Composable
fun GScreen(content: ScalingLazyListScope.() -> Unit) {
    val st = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Scaffold(
        modifier = Modifier.background(G.Black),
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },   // fade at top/bottom edge
        positionIndicator = { PositionIndicator(scalingLazyListState = st) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = st,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 26.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** Concentric progress rings. Values 0..1 (over 1 draws a second lap). Animated, cheap Canvas. */
@Composable
fun Rings(progress: List<Pair<Float, Color>>, size: Dp = 112.dp, stroke: Dp = 13.dp) {
    val animated = progress.map { (p, c) ->
        val v by animateFloatAsState(targetValue = p.coerceIn(0f, 2f), animationSpec = spring(dampingRatio = 0.85f, stiffness = 120f), label = "ring")
        v to c
    }
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        val gap = sw * 0.22f
        animated.forEachIndexed { i, (p, c) ->
            val inset = sw / 2f + i * (sw + gap)
            val tl = Offset(inset, inset)
            val sz = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            if (sz.width <= 0f) return@forEachIndexed
            drawArc(lerp(Color.Black, c, 0.20f), 0f, 360f, false, tl, sz, style = Stroke(sw))   // solid dark track (no alpha blending)
            val sweep = p * 360f
            if (sweep > 0.5f) {
                drawArc(c, -90f, minOf(sweep, 360f), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                if (sweep > 360f) drawArc(c, -90f, sweep - 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
    }
}

/** Ring legend line (Summary template): coloured label, bold white value, goal + unit in colour. */
@Composable
fun StatLine(label: String, value: String, goal: String, color: Color) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = GType.Label, color = color)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = GType.Title, color = G.Text)
            Text("/$goal", style = GType.Caption, color = color, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
    }
}

/** Information tile (List template): rounded rect, coloured label, white value + coloured unit, grey detail. */
@Composable
fun Tile(label: String, value: String, unit: String = "", color: Color, detail: String = "", onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(G.Surface)
    Column((if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, style = GType.Label, color = color)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = GType.Title, color = G.Text)
            if (unit.isNotEmpty()) Text(" $unit", style = GType.Caption, color = color, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (detail.isNotEmpty()) Text(detail, style = GType.Caption, color = G.Muted)
    }
}

/** Detail template header: label, hero number, unit/goal in colour, optional sub line. */
@Composable
fun Hero(label: String, value: String, unit: String, color: Color, sub: String = "") {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = GType.Label, color = color)
        Text(value, style = GType.Hero, color = G.Text)
        Text(unit, style = GType.Caption, color = color)
        if (sub.isNotEmpty()) Text(sub, style = GType.Caption, color = G.Muted, textAlign = TextAlign.Center)
    }
}

/**
 * 24-hour bar chart. Bars at/over `highlightAt` (or all, if null) use the metric colour; others a dark tint.
 * Current hour is drawn even if 0 as a small tick so "now" is visible.
 */
@Composable
fun Bars(values: List<Double>, color: Color, highlightAt: Double? = null, nowHour: Int = -1) {
    val dim = lerp(Color.Black, color, 0.35f)
    val track = G.Surface
    Column(Modifier.fillMaxWidth(0.86f)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val n = 24
            val slot = size.width / n
            val bw = slot * 0.62f
            val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(highlightAt ?: 1.0).coerceAtLeast(1.0)
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            for (i in 0 until n) {
                val v = values.getOrElse(i) { 0.0 }
                val x = i * slot + (slot - bw) / 2f
                val h = if (v > 0) (v / max * (size.height - 2f)).toFloat().coerceAtLeast(3f) else if (i == nowHour) 3f else 0f
                if (h <= 0f) continue
                val c = if (highlightAt == null || v >= highlightAt) color else dim
                drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2f, bw / 2f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18").forEach {
                Text(it, style = GType.Caption, color = G.Muted, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** − value + row used for every adjustable number. */
@Composable
fun Stepper(label: String, value: String, color: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = GType.Label, color = color)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButtons(listOf("−"), sizeDp = 38.dp) { onMinus() }
            Text(value, style = GType.Title, color = G.Text, textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
            RoundButtons(listOf("+"), sizeDp = 38.dp) { onPlus() }
        }
    }
}

/** Capsule button = action. filled = metric/accent colour; otherwise grey. */
@Composable
fun Pill(text: String, filled: Boolean = false, color: Color = G.Accent, sub: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else G.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (sub == null) 11.dp else 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = GType.Body, color = if (filled && color.luminance() > 0.6f) G.Black else G.Text, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, style = GType.Caption, color = if (filled) G.Text.copy(alpha = 0.85f) else G.Muted, textAlign = TextAlign.Center)
    }
}

/** Row of small round buttons (e.g. rested 1–5, −/+). */
@Composable
fun RoundButtons(labels: List<String>, selected: Int = -1, color: Color = G.Accent, sizeDp: Dp = 34.dp, onClick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            Box(
                Modifier.size(sizeDp).clip(CircleShape)
                    .background(if (i == selected) color else G.Surface2)
                    .clickable { onClick(i) },
                contentAlignment = Alignment.Center,
            ) { Text(l, style = GType.Body, color = G.Text) }
        }
    }
}

@Composable
fun Caption(text: String, color: Color = G.Muted) {
    Text(text, style = GType.Caption, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/**
 * Heart-rate line (Detail template). 5-min points, window = first reading of the day (hour-aligned, ≥ 3 h) → now.
 * Colour runs yellow → red with intensity; short gaps (≤ 1 h) dotted, longer gaps left empty. MAX tag above the peak.
 */
@Composable
fun HrLine(points: List<Int>, nowSlot: Int, hrMax: Double) {
    val first = points.indexOfFirst { it > 0 }
    if (first < 0) { Caption("No readings yet today"); return }
    val start = minOf(first / 12 * 12, maxOf(nowSlot - 36, 0))
    val end = minOf(maxOf(nowSlot, start + 36), 287)
    val win = points.subList(start, end + 1)
    val vals = win.filter { it > 0 }
    val peak = vals.max()
    val peakI = win.indexOf(peak)
    val lo = (vals.min() - 6).toFloat()
    val hi = (peak + 6).toFloat()
    val cool = G.Body
    fun tint(v: Float): Color = lerp(cool, G.Heart, ((v - 60f) / (0.85f * hrMax.toFloat() - 60f)).coerceIn(0f, 1f))
    fun label(slot: Int) = "%02d:%02d".format(slot * 5 / 60, slot * 5 % 60)

    BoxWithConstraints(Modifier.fillMaxWidth(0.86f)) {
        val w = maxWidth
        val frac = if (win.size > 1) peakI / (win.size - 1f) else 0.5f
        Column {
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Text("MAX $peak", style = GType.Label, color = G.Text,
                    modifier = Modifier.offset(x = (w * frac - 32.dp).coerceIn(0.dp, w - 66.dp))
                        .clip(RoundedCornerShape(50)).background(tint(peak.toFloat())).padding(horizontal = 7.dp))
            }
            Canvas(Modifier.fillMaxWidth().height(58.dp)) {
                val dx = size.width / (win.size - 1).coerceAtLeast(1)
                fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height
                drawLine(G.Surface2, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.5f)
                drawLine(G.Surface2, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5f)
                val dots = PathEffect.dashPathEffect(floatArrayOf(3f, 6f))
                var prev = -1
                for (i in win.indices) {
                    val v = win[i]; if (v <= 0) continue
                    if (prev >= 0) {
                        val gap = i - prev
                        if (gap <= 12) drawLine(
                            tint((v + win[prev]) / 2f), Offset(prev * dx, y(win[prev])), Offset(i * dx, y(v)),
                            strokeWidth = if (gap == 1) 4f else 2.5f, cap = StrokeCap.Round,
                            pathEffect = if (gap == 1) null else dots,
                        )
                    } else drawCircle(tint(v.toFloat()), 2.5f, Offset(i * dx, y(v)))
                    prev = i
                }
                drawCircle(G.Text, 4f, Offset(peakI * dx, y(peak)))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(label(start), style = GType.Caption, color = G.Muted, modifier = Modifier.weight(1f))
                Text(label((start + end) / 2), style = GType.Caption, color = G.Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("now", style = GType.Caption, color = G.Muted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
    }
}

/** Time in heart-rate zones 1–5 as one stacked bar + labels. */
val ZoneColors = listOf(G.Water, G.Exercise, G.Body, G.Warn, G.Heart)

@Composable
fun ZoneBar(seconds: List<Long>) {
    val total = seconds.sum().coerceAtLeast(1L).toFloat()
    Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))) {
            drawRect(G.Surface2)
            var x = 0f
            seconds.forEachIndexed { i, v ->
                val w = v / total * size.width
                if (w > 0f) drawRect(ZoneColors[i], Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
        Row(Modifier.fillMaxWidth()) {
            seconds.forEachIndexed { i, v ->
                Text("Z${i + 1} ${v / 60}m", style = GType.Caption, color = ZoneColors[i],
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Few labelled bars (e.g. last 7 nights). Dashed goal line if given. */
@Composable
fun DayBars(values: List<Double>, labels: List<String>, color: Color, goal: Double? = null) {
    val dim = lerp(Color.Black, color, 0.35f)
    Column(Modifier.fillMaxWidth(0.86f)) {
        Canvas(Modifier.fillMaxWidth().height(60.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = slot * 0.58f
            val max = maxOf(values.maxOrNull() ?: 0.0, goal ?: 0.0, 1.0)
            drawLine(G.Surface, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            if (goal != null) {
                val y = (size.height - goal / max * (size.height - 2f)).toFloat()
                drawLine(G.Muted, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (v / max * (size.height - 2f)).toFloat().coerceAtLeast(3f)
                val x = i * slot + (slot - bw) / 2f
                drawRoundRect(if (goal == null || v >= goal) color else dim, Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 2f, bw / 2f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, style = GType.Caption, color = G.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}

/** Simple line for a short series (0 = gap), e.g. heart rate through the night. */
@Composable
fun MiniLine(points: List<Int>, color: Color, startLabel: String = "", endLabel: String = "") {
    val vals = points.filter { it > 0 }
    if (vals.size < 2) { Caption("Not enough heart-rate readings"); return }
    val lo = vals.min() - 3; val hi = vals.max() + 3
    Column(Modifier.fillMaxWidth(0.86f)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val dx = size.width / (points.size - 1).coerceAtLeast(1)
            fun y(v: Int) = size.height - (v - lo).toFloat() / (hi - lo) * size.height
            var prev: Offset? = null
            points.forEachIndexed { i, v ->
                if (v <= 0) { prev = null; return@forEachIndexed }
                val p = Offset(i * dx, y(v))
                prev?.let { drawLine(color, it, p, strokeWidth = 3f, cap = StrokeCap.Round) }
                prev = p
            }
            val mi = points.indexOf(vals.min())
            drawCircle(color, 5f, Offset(mi * dx, y(vals.min())))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(startLabel, style = GType.Caption, color = G.Muted, modifier = Modifier.weight(1f))
            Text("low ${vals.min()}", style = GType.Caption, color = color)
            Text(endLabel, style = GType.Caption, color = G.Muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}
