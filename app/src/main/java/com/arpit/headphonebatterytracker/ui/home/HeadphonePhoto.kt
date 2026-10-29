package com.arpit.headphonebatterytracker.ui.home

import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arpit.headphonebatterytracker.headphones.PhotoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FLOAT_CYCLES = 2

/** Decoded photos kept for the life of the app, so scrolling never reloads them. */
private object PhotoCache {
    val images = mutableMapOf<String, ImageBitmap?>()

    /** True once the entrance animation has played; it only plays when the app opens. */
    var entrancePlayed = false
}

/** Loads a headphone photo from assets; null if the file isn't there (then the drawing is used). */
@Composable
fun rememberHeadphonePhoto(spec: PhotoSpec?): ImageBitmap? {
    val context = LocalContext.current
    val photo by produceState(initialValue = spec?.let { PhotoCache.images[it.assetPath] }, spec) {
        if (spec == null) {
            value = null
            return@produceState
        }
        if (PhotoCache.images.containsKey(spec.assetPath)) {
            value = PhotoCache.images[spec.assetPath]
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) {
            try {
                context.assets.open(spec.assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (e: Exception) {
                null
            }
        }
        PhotoCache.images[spec.assetPath] = loaded
        value = loaded
    }
    return photo
}

/**
 * The real product photo with a little life: it eases in once when shown, floats slowly, and sits
 * on a soft glow in the current mode's colour.
 */
@Composable
fun HeadphonePhoto(
    photo: ImageBitmap,
    spec: PhotoSpec,
    glowColor: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motionOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    // One entrance per app launch: fade and settle from slightly smaller.
    val playEntrance = motionOn && !PhotoCache.entrancePlayed
    val entrance = remember { Animatable(if (playEntrance) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (playEntrance) {
            PhotoCache.entrancePlayed = true
            entrance.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
    }

    // Floats gently for a few seconds when shown and when the mode colour changes, then rests.
    // A resting screen draws no frames, which matters at 120 Hz.
    val float = remember { Animatable(0f) }
    LaunchedEffect(glowColor) {
        if (!motionOn) return@LaunchedEffect
        repeat(FLOAT_CYCLES) {
            float.animateTo(1f, tween(1600, easing = FastOutSlowInEasing))
            float.animateTo(-1f, tween(3200, easing = FastOutSlowInEasing))
        }
        float.animateTo(0f, tween(1600, easing = FastOutSlowInEasing))
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .aspectRatio(photo.width.toFloat() / photo.height),
    ) {
        // Soft glow in the mode colour so black headphones stand out on the dark background.
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = entrance.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) {
            val radius = maxOf(size.width, size.height) * 0.6f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor.copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        Image(
            bitmap = photo,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entrance.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    val s = 0.92f + 0.08f * entrance.value
                    scaleX = s
                    scaleY = s
                    // Read here (not in composition) so the float only moves the layer each frame.
                    translationY = float.value * 4f * density
                },
        )

    }
}
