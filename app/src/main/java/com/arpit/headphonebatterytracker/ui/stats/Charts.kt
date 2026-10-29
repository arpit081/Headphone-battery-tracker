package com.arpit.headphonebatterytracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arpit.headphonebatterytracker.headphones.TrackingMode
import com.arpit.headphonebatterytracker.tracking.Sample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Mode colors: the first three slots of the validated categorical palette (blue, orange, aqua),
 * which stay distinguishable for colour-blind viewers. Dark-mode steps are used on dark themes.
 * Identity never relies on colour alone: every chart has a legend or a text label next to the mark.
 */
@Composable
fun modeColor(mode: TrackingMode): Color {
    val dark = isSystemInDarkTheme()
    return when (mode) {
        TrackingMode.ANC -> if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
        TrackingMode.AMBIENT -> if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
        TrackingMode.NORMAL -> if (dark) Color(0xFF199E70) else Color(0xFF1BAF7A)
        TrackingMode.OFF -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun ModeLegend(modes: List<TrackingMode>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        for (mode in modes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(modeColor(mode), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(mode.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Gap between samples after which the line is broken (headphones off, app stopped, charging). */
private const val LINE_GAP_MS = 7 * 60 * 1000L

fun formatTime(millis: Long, withDate: Boolean): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (withDate) "d MMM HH:mm" else "HH:mm"))

/**
 * Battery % over time, one 2dp line coloured by the mode at each moment. Tap or drag to read a value.
 */
@Composable
fun BatteryTimelineChart(samples: List<Sample>, start: Long, end: Long) {
    val points = remember(samples) { samples.filter { it.battery != null } }
    var selected by remember(points) { mutableStateOf<Sample?>(null) }
    val multiDay = end - start > 24 * 60 * 60 * 1000L
    val grid = MaterialTheme.colorScheme.outlineVariant
    val crosshair = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val colors = TrackingMode.entries.associateWith { modeColor(it) }
    val span = (end - start).coerceAtLeast(1L)

    fun nearest(x: Float, width: Int): Sample? {
        if (points.isEmpty() || width <= 0) return null
        val t = start + (x / width * span).toLong()
        return points.minByOrNull { abs(it.timestamp - t) }
    }

    Column {
        Text(
            selected?.let {
                "${formatTime(it.timestamp, multiDay)} · ${it.battery}% · ${it.mode.label} · volume ${it.volumePercent}%"
            } ?: "Tap or drag on the chart to read a value",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Column(
                Modifier.height(180.dp).width(36.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                for (label in listOf("100%", "50%", "0%")) {
                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(180.dp)
                    .pointerInput(points, start, end) {
                        detectTapGestures { offset -> selected = nearest(offset.x, size.width) }
                    }
                    .pointerInput(points, start, end) {
                        detectHorizontalDragGestures { change, _ -> selected = nearest(change.position.x, size.width) }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(t: Long) = (t - start).toFloat() / span * w
                fun y(b: Int) = h - b / 100f * h

                for (level in listOf(0, 50, 100)) {
                    drawLine(grid, Offset(0f, y(level)), Offset(w, y(level)), strokeWidth = 1.dp.toPx())
                }
                for ((a, b) in points.zipWithNext()) {
                    if (b.timestamp - a.timestamp > LINE_GAP_MS) continue
                    drawLine(
                        color = colors.getValue(a.mode),
                        start = Offset(x(a.timestamp), y(a.battery!!)),
                        end = Offset(x(b.timestamp), y(b.battery!!)),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                selected?.let { s ->
                    val sx = x(s.timestamp)
                    val sy = y(s.battery!!)
                    drawLine(crosshair.copy(alpha = 0.6f), Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.dp.toPx())
                    drawCircle(surface, radius = 6.dp.toPx(), center = Offset(sx, sy))
                    drawCircle(colors.getValue(s.mode), radius = 4.dp.toPx(), center = Offset(sx, sy))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
            val mid = start + span / 2
            Text(formatTime(start, multiDay), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(formatTime(mid, multiDay), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(formatTime(end, multiDay), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

/**
 * One horizontal bar: label, bar from the baseline (4dp rounded end), value text, and an optional
 * reference tick (e.g. the company's claim).
 */
@Composable
fun BarRow(
    label: String,
    value: Double,
    max: Double,
    valueText: String,
    color: Color,
    reference: Double? = null,
) {
    val tickColor = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), fontSize = 13.sp)
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val safeMax = if (max > 0) max else 1.0
            // Thin baseline so empty bars still read as "zero".
            drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
            val fraction = (value / safeMax).toFloat().coerceIn(0f, 1f)
            if (value > 0) {
                val barWidth = (size.width * fraction).coerceAtLeast(4.dp.toPx())
                drawRoundRect(color, size = Size(barWidth, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
            }
            reference?.let { r ->
                val rx = (r / safeMax).toFloat().coerceIn(0f, 1f) * size.width
                drawLine(tickColor, Offset(rx, -3.dp.toPx()), Offset(rx, size.height + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
        Text(valueText, Modifier.width(96.dp).padding(start = 8.dp), fontSize = 13.sp)
    }
}
