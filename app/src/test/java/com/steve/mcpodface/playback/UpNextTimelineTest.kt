package com.steve.mcpodface.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The player's playlist mirrors "playing episode, then Up Next" (docs/ANDROID-AUTO.md, A12). */
@RunWith(AndroidJUnit4::class)
class UpNextTimelineTest {
    private lateinit var player: ExoPlayer
    private lateinit var timeline: UpNextTimeline
    private val unresolvable = mutableSetOf<Long>()

    @Before fun setUp() {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setLooper(Looper.getMainLooper())
            .build()
        timeline = UpNextTimeline(player) { id -> if (id in unresolvable) null else item(id) }
    }

    @After fun tearDown() = player.release()

    private fun item(id: Long) = MediaItem.Builder().setMediaId(id.toString()).setUri("https://example.invalid/$id.mp3").build()

    private fun ids(p: Player = player): List<Long?> = List(p.mediaItemCount) { p.getMediaItemAt(it).episodeId() }

    @Test fun addsUpNextAfterThePlayingEpisode() = runBlocking<Unit> {
        player.setMediaItem(item(1))

        timeline.sync(listOf(2, 3))

        assertThat(ids()).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    @Test fun thePlayingEpisodeIsNotListedTwiceWhenItIsAlsoInUpNext() = runBlocking<Unit> {
        player.setMediaItem(item(2))

        timeline.sync(listOf(1, 2, 3))

        assertThat(ids()).containsExactly(2L, 1L, 3L).inOrder()
    }

    @Test fun followsReorderAndRemoval() = runBlocking<Unit> {
        player.setMediaItem(item(1))
        timeline.sync(listOf(2, 3, 4))

        timeline.sync(listOf(4, 2))

        assertThat(ids()).containsExactly(1L, 4L, 2L).inOrder()
    }

    @Test fun dropsEpisodesBeforeThePlayingOneAfterAJump() = runBlocking<Unit> {
        player.setMediaItem(item(1))
        timeline.sync(listOf(2, 3, 4))

        // A car queue tap on episode 3.
        player.seekToDefaultPosition(2)
        timeline.sync(listOf(2, 3, 4))

        assertThat(player.currentMediaItem?.episodeId()).isEqualTo(3L)
        assertThat(ids()).containsExactly(3L, 2L, 4L).inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(0)
    }

    @Test fun emptyUpNextLeavesOnlyThePlayingEpisode() = runBlocking<Unit> {
        player.setMediaItem(item(1))
        timeline.sync(listOf(2, 3))

        timeline.sync(emptyList())

        assertThat(ids()).containsExactly(1L)
    }

    @Test fun nothingLoadedStaysEmpty() = runBlocking<Unit> {
        timeline.sync(listOf(2, 3))

        assertThat(player.mediaItemCount).isEqualTo(0)
    }

    @Test fun episodesThatCannotBeResolvedAreSkipped() = runBlocking<Unit> {
        unresolvable += 3L
        player.setMediaItem(item(1))

        timeline.sync(listOf(2, 3, 4))

        assertThat(ids()).containsExactly(1L, 2L, 4L).inOrder()
    }

    @Test fun inSyncNeedsThePlayingEpisodeFirstAndUpNextAfterIt() {
        assertThat(UpNextTimeline.inSync(0, listOf(1L, 2L, 3L), listOf(2L, 3L))).isTrue()
        assertThat(UpNextTimeline.inSync(1, listOf(9L, 1L, 2L, 3L), listOf(2L, 3L))).isFalse()
        assertThat(UpNextTimeline.inSync(0, listOf(1L, 3L, 2L), listOf(2L, 3L))).isFalse()
        assertThat(UpNextTimeline.upNextAfter(2L, listOf(1L, 2L, 3L))).containsExactly(1L, 3L).inOrder()
    }
}
