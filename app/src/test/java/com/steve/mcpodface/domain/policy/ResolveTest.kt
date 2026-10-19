package com.steve.mcpodface.domain.policy

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.prefs.GlobalSettings
import org.junit.Test

class ResolveTest {
    private val global = GlobalSettings()

    @Test fun noOverrideUsesGlobalDefaults() {
        assertThat(resolve(global, null, FeedType.EPISODIC)).isEqualTo(
            EffectiveSettings(
                autoDownload = true, keepLatestCount = 5, deletePlayed = true, deletePlayedDelayHours = 24,
                maxAgeDays = 30, allowCellular = false, sortOrder = SortOrder.NEWEST_FIRST,
                countManualTowardLimit = false,
            ),
        )
    }

    @Test fun allNullOverrideFieldsInherit() {
        assertThat(resolve(global, PodcastSettingsEntity(podcastId = 1), FeedType.EPISODIC))
            .isEqualTo(resolve(global, null, FeedType.EPISODIC))
    }

    @Test fun everyOverrideWins() {
        val o = PodcastSettingsEntity(
            podcastId = 1, autoDownload = false, keepLatestCount = 2, deletePlayed = false,
            maxAgeDays = 0, allowCellular = true, sortOrder = SortOrder.OLDEST_FIRST,
        )
        val r = resolve(global, o, FeedType.EPISODIC)
        assertThat(r.autoDownload).isFalse()
        assertThat(r.keepLatestCount).isEqualTo(2)
        assertThat(r.deletePlayed).isFalse()
        assertThat(r.maxAgeDays).isEqualTo(0)
        assertThat(r.allowCellular).isTrue()
        assertThat(r.sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
    }

    @Test fun globalOnlyFieldsIgnorePodcast() {
        val g = global.copy(deletePlayedDelayHours = 72, countManualTowardLimit = true)
        val r = resolve(g, PodcastSettingsEntity(podcastId = 1, keepLatestCount = 3), FeedType.EPISODIC)
        assertThat(r.deletePlayedDelayHours).isEqualTo(72)
        assertThat(r.countManualTowardLimit).isTrue()
    }

    @Test fun serialFeedDefaultsToOldestFirst() {
        assertThat(resolve(global, null, FeedType.SERIAL).sortOrder).isEqualTo(SortOrder.OLDEST_FIRST)
    }

    @Test fun explicitSortBeatsFeedType() {
        val o = PodcastSettingsEntity(podcastId = 1, sortOrder = SortOrder.NEWEST_FIRST)
        assertThat(resolve(global, o, FeedType.SERIAL).sortOrder).isEqualTo(SortOrder.NEWEST_FIRST)
    }

    @Test fun keepLatestCountIsClampedToOneToTwenty() {
        assertThat(resolve(global, PodcastSettingsEntity(1, keepLatestCount = 0), FeedType.EPISODIC).keepLatestCount)
            .isEqualTo(1)
        assertThat(resolve(global, PodcastSettingsEntity(1, keepLatestCount = 99), FeedType.EPISODIC).keepLatestCount)
            .isEqualTo(20)
    }
}
