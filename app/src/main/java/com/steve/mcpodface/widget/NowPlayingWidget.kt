package com.steve.mcpodface.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders as ColorProvidersFactory
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.steve.mcpodface.MainActivity
import com.steve.mcpodface.R
import com.steve.mcpodface.playback.NowPlaying
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * Home-screen mini player: artwork, episode title, podcast name and skip / play-pause controls.
 * It shows the loaded episode, or the last played one when nothing is loaded. [WidgetUpdater]
 * pushes updates when playback changes.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 56.dp), // 2x1: artwork and play button
            DpSize(250.dp, 56.dp), // 4x1: one row with title and all controls
            DpSize(250.dp, 120.dp), // 4x2: artwork and title above the controls
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = widgetEntryPoint(context)
        val nowPlaying: NowPlaying? = deps.playerController().nowPlaying.value
        val nowPlayingEpisode = nowPlaying?.let { np ->
            deps.episodeDao().getById(np.episodeId)?.let { deps.widgetEpisode(it) }
        }
        val lastPlayed = deps.episodeDao().getLastPlayed()?.let { deps.widgetEpisode(it) }
        val content = resolveWidgetContent(nowPlaying, nowPlayingEpisode, lastPlayed)
        val artwork = (content as? WidgetContent.Episode)?.episode?.artworkUrl?.let { loadArtwork(context, it) }

        provideContent {
            GlanceTheme(colors = WidgetTheme.colors) {
                WidgetBody(content = content, artwork = artwork)
            }
        }
    }
}

/** A small bitmap for the widget (RemoteViews cannot take hardware bitmaps). Null if loading fails. */
private suspend fun loadArtwork(context: Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(ARTWORK_PX, ARTWORK_PX)
        .allowHardware(false) // RemoteViews cannot carry hardware bitmaps
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (e: Exception) {
    null
}

private const val ARTWORK_PX = 200

@Composable
private fun WidgetBody(content: WidgetContent, artwork: Bitmap?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.width < 200.dp
    val tall = size.height >= 100.dp
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

    Box(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.surface)
            .padding(8.dp),
    ) {
        when (content) {
            WidgetContent.Empty -> Row(
                modifier = GlanceModifier.fillMaxSize().clickable(openApp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(artwork, GlanceModifier.size(40.dp), context.getString(R.string.widget_artwork))
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    text = context.getString(R.string.widget_nothing_playing),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            is WidgetContent.Episode -> {
                val episode = content.episode
                val playPause = actionRunCallback<PlayPauseAction>(
                    actionParametersOf(EpisodeIdKey to episode.episodeId),
                )
                val controls = WidgetControls(
                    isPlaying = content.isPlaying,
                    showSkip = !compact,
                    playPause = playPause,
                )
                if (tall) {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        ArtworkImage(
                            artwork,
                            GlanceModifier.size(88.dp).clickable(openApp),
                            context.getString(R.string.widget_artwork),
                        )
                        Spacer(GlanceModifier.height(6.dp))
                        EpisodeText(episode.title, episode.podcastTitle, openApp, GlanceModifier.fillMaxWidth())
                        Spacer(GlanceModifier.defaultWeight())
                        controls.Render(centered = true)
                    }
                } else {
                    Row(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(
                            artwork,
                            GlanceModifier.size(40.dp).clickable(openApp),
                            context.getString(R.string.widget_artwork),
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        EpisodeText(episode.title, episode.podcastTitle, openApp, GlanceModifier.defaultWeight())
                        controls.Render(centered = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkImage(artwork: Bitmap?, modifier: GlanceModifier, description: String) {
    val provider = artwork?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.widget_artwork_placeholder)
    Image(
        provider = provider,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier.cornerRadius(12.dp),
    )
}

@Composable
private fun EpisodeText(title: String, podcastTitle: String?, openApp: Action, modifier: GlanceModifier) {
    Column(modifier = modifier.clickable(openApp)) {
        Text(
            text = title,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (podcastTitle != null) {
            Text(
                text = podcastTitle,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
    }
}

/** Skip back, play/pause and skip forward. Skip buttons are left out in the 2x1 size. */
private class WidgetControls(
    private val isPlaying: Boolean,
    private val showSkip: Boolean,
    private val playPause: Action,
) {
    @Composable
    fun Render(centered: Boolean) {
        val context = LocalContext.current
        val playLabel = context.getString(if (isPlaying) R.string.widget_pause else R.string.widget_play)
        val playIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        Row(
            modifier = GlanceModifier.padding(top = if (centered) 6.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSkip) {
                IconButton(
                    R.drawable.ic_widget_skip_back,
                    context.getString(R.string.widget_skip_back),
                    actionRunCallback<SkipBackAction>(),
                )
                Spacer(GlanceModifier.width(4.dp))
            }
            Box(
                modifier = GlanceModifier.size(44.dp)
                    .cornerRadius(22.dp)
                    .background(GlanceTheme.colors.primary)
                    .clickable(playPause),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(playIcon),
                    contentDescription = playLabel,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
            if (showSkip) {
                Spacer(GlanceModifier.width(4.dp))
                IconButton(
                    R.drawable.ic_widget_skip_forward,
                    context.getString(R.string.widget_skip_forward),
                    actionRunCallback<SkipForwardAction>(),
                )
            }
        }
    }
}

@Composable
private fun IconButton(icon: Int, description: String, action: Action) {
    Box(
        // 48dp touch target (the icon itself stays 24dp).
        modifier = GlanceModifier.size(48.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            modifier = GlanceModifier.size(24.dp),
        )
    }
}

/** Colours close to the app's Material 3 scheme (violet accent, light and dark). */
internal object WidgetTheme {
    val colors: androidx.glance.color.ColorProviders = ColorProvidersFactory(
        light = lightColorScheme(
            primary = Color(0xFF6B4EFF),
            onPrimary = Color(0xFFFFFFFF),
            surface = Color(0xD9FFFFFF),
            onSurface = Color(0xFF111114),
            onSurfaceVariant = Color(0xFF5A5A62),
        ),
        dark = darkColorScheme(
            primary = Color(0xFF9B8CFF),
            onPrimary = Color(0xFF1A1040),
            surface = Color(0xCC1C1B22),
            onSurface = Color(0xFFF2F2F7),
            onSurfaceVariant = Color(0xFFAEAEB2),
        ),
    )
}
