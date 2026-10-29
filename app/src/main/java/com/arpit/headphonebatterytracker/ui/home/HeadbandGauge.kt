package com.arpit.headphonebatterytracker.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val BAND_STROKE = 16.dp
private val CUP_WIDTH = 34.dp
private val CUP_HEIGHT = 64.dp
private val TOP_GAP = 4.dp

/**
 * Battery gauge drawn as a pair of headphones: the headband is the meter (fills left to right
 * with [color]) and an ear cup sits at each end. [content] is placed inside the band.
 */
@Composable
fun HeadbandGauge(
    level: Int?,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val cup = MaterialTheme.colorScheme.surfaceContainerHigh
    val progress by animateFloatAsState(targetValue = (level ?: 0) / 100f, label = "battery")

    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val diameter = maxWidth - CUP_WIDTH
        val bandEndY = BAND_STROKE / 2 + TOP_GAP + diameter / 2
        val totalHeight = bandEndY - 4.dp + CUP_HEIGHT

        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = BAND_STROKE.toPx()
                val cupW = CUP_WIDTH.toPx()
                val left = cupW / 2
                val top = stroke / 2 + TOP_GAP.toPx()
                val d = size.width - cupW
                val arcSize = Size(d, d)
                val style = Stroke(width = stroke, cap = StrokeCap.Round)

                drawArc(track, 180f, 180f, false, Offset(left, top), arcSize, style = style)
                if (level != null && level > 0) {
                    drawArc(color, 180f, 180f * progress, false, Offset(left, top), arcSize, style = style)
                }

                val endY = top + d / 2
                for (cx in listOf(left, left + d)) {
                    drawRoundRect(
                        color = cup,
                        topLeft = Offset(cx - cupW / 2, endY - 4.dp.toPx()),
                        size = Size(cupW, CUP_HEIGHT.toPx()),
                        cornerRadius = CornerRadius(cupW / 2),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = CUP_HEIGHT - 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}
