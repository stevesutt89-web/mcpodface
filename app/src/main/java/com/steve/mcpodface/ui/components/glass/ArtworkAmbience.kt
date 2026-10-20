package com.steve.mcpodface.ui.components.glass

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import coil3.toBitmap
import com.steve.mcpodface.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A soft wash of the artwork's colours: the image hugely scaled and blurred, fading into the page
 * background towards the bottom. Used behind the podcast page header, episode page and full player,
 * and recorded by [GlassPage] so glass controls refract it.
 * [fadeTo] is where the wash has faded completely (0 = top, 1 = bottom).
 */
@Composable
fun ArtworkAmbience(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    fadeTo: Float = 0.75f,
    intensity: Float = 1f,
) {
    val background = MaterialTheme.colorScheme.background
    val dark = AppTheme.colors.isDark
    Box(modifier.fillMaxSize().background(background)) {
        // Reduce visual effects: no artwork glow, just the page colour.
        if (artworkUrl != null && !LocalGlassEffects.current.reduced && !LocalInspectionMode.current) {
            // The artwork decoded tiny, blurred once on the CPU (cached by Coil), then stretched with
            // bilinear filtering: a smooth wash at almost no cost. Unblurred, the stretched pixels
            // showed as blocks. (A full-screen blur(80.dp) was re-rendered by every glass control on
            // every scroll frame.)
            val context = LocalContext.current
            val request = remember(artworkUrl) {
                ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(AMBIENCE_PX)
                    .transformations(AmbienceBlurTransformation)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                alpha = (if (dark) 0.75f else 0.6f) * intensity,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.4f
                        scaleY = 1.4f
                    },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = if (dark) 0.15f else 0.05f),
                    fadeTo to background,
                    1f to background,
                ),
            ),
        )
    }
}

/**
 * A vivid, readable accent taken from the artwork (Palette vibrant swatch), for the player and
 * podcast pages. null while loading or when the artwork has no usable colour.
 */
@Composable
fun rememberArtworkAccent(artworkUrl: String?): State<Color?> {
    val context = LocalContext.current
    val dark = AppTheme.colors.isDark
    return produceState<Color?>(null, artworkUrl, dark) {
        if (artworkUrl == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(false)
                    .size(160)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                    ?: return@runCatching null
                val palette = Palette.from(result.image.toBitmap()).maximumColorCount(16).generate()
                val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch
                    ?: palette.dominantSwatch ?: return@runCatching null
                readableAccent(swatch.rgb, dark)
            }.getOrNull()
        }
    }
}

/** Clamps saturation/lightness so the accent works for tinted glass with white text and as a tint. */
internal fun readableAccent(argb: Int, dark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = hsl[1].coerceIn(0.45f, 0.9f)
    hsl[2] = if (dark) hsl[2].coerceIn(0.55f, 0.7f) else hsl[2].coerceIn(0.38f, 0.52f)
    return Color(ColorUtils.HSLToColor(hsl))
}

internal fun Color.argbInt(): Int = toArgb()

private const val AMBIENCE_PX = 48

/** Blurs the tiny ambience artwork so it doesn't look pixelated when stretched (see [boxBlurArgb]). */
private object AmbienceBlurTransformation : Transformation() {
    private const val RADIUS = 4

    override val cacheKey: String = "ambienceBlur-$RADIUS"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurred = boxBlurArgb(pixels, w, h, RADIUS)
        return Bitmap.createBitmap(blurred, w, h, Bitmap.Config.ARGB_8888)
    }
}
