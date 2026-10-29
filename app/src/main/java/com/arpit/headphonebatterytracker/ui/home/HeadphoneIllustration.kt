package com.arpit.headphonebatterytracker.ui.home

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the sound waves around the ear cups do. */
enum class WaveStyle { NONE, INWARD, OUTWARD }

// Proportions of an XM5-style over-ear headphone, as fractions of the drawing width.
private const val BAND_RADIUS = 0.29f
private const val ARM_LENGTH = 0.07f
private const val CUP_WIDTH = 0.17f
private const val CUP_HEIGHT = 0.28f
private const val WAVE_REACH = 0.10f
private val BAND_STROKE = 12.dp
private val TOP_GAP = 8.dp

/**
 * Front view of over-ear headphones (styled after the WH-1000XM5), drawn in code.
 * The headband is the battery meter. Waves beside the cups show the noise-control mode:
 * inward for ANC, outward for Ambient. The band pulses while charging.
 */
@Composable
fun HeadphoneIllustration(
    level: Int?,
    color: Color,
    waves: WaveStyle,
    charging: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val motionOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val progress by animateFloatAsState(targetValue = (level ?: 0) / 100f, label = "battery")
    // Only animate while charging; otherwise nothing redraws when the battery isn't changing.
    val pulse: State<Float>? = if (charging && motionOn) {
        rememberInfiniteTransition(label = "charging").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "pulse",
        )
    } else {
        null
    }
    val phase = 0.5f

    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val shellTop = MaterialTheme.colorScheme.surfaceContainerHighest
    val shellBottom = MaterialTheme.colorScheme.surfaceContainerHigh
    val cushion = MaterialTheme.colorScheme.outlineVariant
    val arm = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val w = maxWidth
        val bandCenterY = TOP_GAP + BAND_STROKE / 2 + w * BAND_RADIUS
        val totalHeight = bandCenterY + w * (ARM_LENGTH + CUP_HEIGHT) + 4.dp

        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                val width = size.width
                val cx = width / 2
                val r = width * BAND_RADIUS
                val cy = TOP_GAP.toPx() + BAND_STROKE.toPx() / 2 + r
                val armLen = width * ARM_LENGTH
                val cupW = width * CUP_WIDTH
                val cupH = width * CUP_HEIGHT

                // Arms: thin sliders from the band ends down to the cups.
                for (side in listOf(-1f, 1f)) {
                    val x = cx + side * r
                    drawLine(arm, Offset(x, cy - 2.dp.toPx()), Offset(x, cy + armLen + 6.dp.toPx()), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                }

                // Headband = battery meter.
                val bandTopLeft = Offset(cx - r, cy - r)
                val bandSize = Size(2 * r, 2 * r)
                val bandStyle = Stroke(width = BAND_STROKE.toPx(), cap = StrokeCap.Round)
                drawArc(track, 180f, 180f, false, bandTopLeft, bandSize, style = bandStyle)
                if (level != null && level > 0) {
                    val bandAlpha = pulse?.value ?: 1f
                    drawArc(color.copy(alpha = bandAlpha), 180f, 180f * progress, false, bandTopLeft, bandSize, style = bandStyle)
                }

                // Ear cups and the waves beside them.
                for (side in listOf(-1f, 1f)) {
                    val cupCenter = Offset(cx + side * r, cy + armLen + cupH / 2)
                    drawWaves(waves, side, cupCenter, cupW, width * WAVE_REACH, phase, color)
                    drawCup(side, cupCenter, cupW, cupH, shellTop, shellBottom, cushion)
                }
            }

            // Battery % sits inside the headband.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = TOP_GAP + w * BAND_RADIUS * 0.42f),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/** One ear cup seen from the front: a tall rounded shell, with the cushion showing on the inner side. */
private fun DrawScope.drawCup(
    side: Float,
    center: Offset,
    cupW: Float,
    cupH: Float,
    shellTop: Color,
    shellBottom: Color,
    cushion: Color,
) {
    // Cushion: slightly shorter, peeking out on the side facing the head.
    val cushionW = cupW * 0.42f
    val cushionH = cupH * 0.86f
    val cushionX = if (side < 0) center.x + cupW / 2 - cushionW * 0.55f else center.x - cupW / 2 - cushionW * 0.45f
    drawRoundRect(
        color = cushion,
        topLeft = Offset(cushionX, center.y - cushionH / 2),
        size = Size(cushionW, cushionH),
        cornerRadius = CornerRadius(cushionW / 2),
    )
    // Shell with a soft top-to-bottom shade.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(shellTop, shellBottom), startY = center.y - cupH / 2, endY = center.y + cupH / 2),
        topLeft = Offset(center.x - cupW / 2, center.y - cupH / 2),
        size = Size(cupW, cupH),
        cornerRadius = CornerRadius(cupW / 2),
    )
    // Two small microphone openings near the top of the shell.
    val dot = 1.6.dp.toPx()
    for (i in 0..1) {
        drawCircle(cushion, radius = dot, center = Offset(center.x - side * cupW * 0.12f + i * side * cupW * 0.18f, center.y - cupH * 0.32f))
    }
}

/**
 * Three arcs on the outer side of a cup. OUTWARD: they grow away from the cup and fade (sound let in).
 * INWARD: they shrink toward the cup and fade as they reach it (noise blocked).
 */
private fun DrawScope.drawWaves(
    style: WaveStyle,
    side: Float,
    cupCenter: Offset,
    cupW: Float,
    reach: Float,
    phase: Float,
    color: Color,
) {
    if (style == WaveStyle.NONE) return
    val start = cupW / 2 + 6.dp.toPx()
    // Arc faces away from the head: left cup → left (180°), right cup → right (0°).
    val startAngle = if (side < 0) 140f else -40f
    for (k in 0 until 3) {
        val t = (phase + k / 3f) % 1f
        val f = if (style == WaveStyle.OUTWARD) t else 1f - t
        val radius = start + reach * f
        val alpha = if (style == WaveStyle.OUTWARD) (1f - f) * 0.8f else f * 0.8f
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = startAngle,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(cupCenter.x - radius, cupCenter.y - radius),
            size = Size(2 * radius, 2 * radius),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
