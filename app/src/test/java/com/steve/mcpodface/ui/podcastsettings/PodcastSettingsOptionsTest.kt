package com.steve.mcpodface.ui.podcastsettings

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.db.FeedType
import com.steve.mcpodface.data.db.PodcastSettingsEntity
import com.steve.mcpodface.data.db.QueueAdd
import com.steve.mcpodface.data.db.SortOrder
import com.steve.mcpodface.data.prefs.GlobalSettings
import org.junit.Test

class PodcastSettingsOptionsTest {
    private val blank = PodcastSettingsEntity(podcastId = 7L)
    private val episodic = Inheritance(GlobalSettings(), FeedType.EPISODIC)
    private val serial = Inheritance(GlobalSettings(), FeedType.SERIAL)
    private val o = PodcastSettingsOptions

    @Test fun autoDownloadDefaultLabelFollowsGlobalOn() {
        assertThat(o.autoDownload.options(episodic).first().second).isEqualTo("Default (On)")
    }

    @Test fun autoDownloadDefaultLabelFollowsGlobalOff() {
        val ctx = Inheritance(GlobalSettings(autoDownload = false), FeedType.EPISODIC)
        assertThat(o.autoDownload.options(ctx).first().second).isEqualTo("Default (Off)")
        assertThat(o.autoDownload.label(blank, ctx)).isEqualTo("Default (Off)")
    }

    @Test fun keepLatestDefaultLabelShowsGlobalCount() {
        assertThat(o.keepLatest.label(blank, episodic)).isEqualTo("Default (5)")
        val ctx = Inheritance(GlobalSettings(keepLatestCount = 3), FeedType.EPISODIC)
        assertThat(o.keepLatest.label(blank, ctx)).isEqualTo("Default (3)")
    }

    @Test fun maxAgeDefaultLabelUsesGlobalAgeName() {
        assertThat(o.maxAgeDays.label(blank, episodic)).isEqualTo("Default (1 month)")
        val never = Inheritance(GlobalSettings(maxAgeDays = 0), FeedType.EPISODIC)
        assertThat(o.maxAgeDays.label(blank, never)).isEqualTo("Default (Never)")
    }

    @Test fun allowCellularDefaultLabelShowsGlobalNetworkRule() {
        assertThat(o.allowCellular.label(blank, episodic)).isEqualTo("Default (Wi-Fi only)")
        val cellular = Inheritance(GlobalSettings(allowCellularDownload = true), FeedType.EPISODIC)
        assertThat(o.allowCellular.label(blank, cellular)).isEqualTo("Default (Wi-Fi and mobile data)")
    }

    @Test fun serialFeedDefaultSortLabelIsOldestFirst() {
        assertThat(o.sortOrder.label(blank, serial)).isEqualTo("Default (Oldest first)")
    }

    @Test fun episodicFeedDefaultSortLabelIsNewestFirst() {
        assertThat(o.sortOrder.label(blank, episodic)).isEqualTo("Default (Newest first)")
    }

    @Test fun explicitSortOverrideLabelIgnoresFeedDefault() {
        val entity = o.sortOrder.apply(blank, SortOrder.NEWEST_FIRST)
        assertThat(o.sortOrder.label(entity, serial)).isEqualTo("Newest first")
    }

    @Test fun choosingDefaultWritesNullForEveryInheritableSetting() {
        val full = PodcastSettingsEntity(
            podcastId = 7L,
            autoDownload = true,
            keepLatestCount = 10,
            deletePlayed = false,
            maxAgeDays = 90,
            allowCellular = true,
            sortOrder = SortOrder.OLDEST_FIRST,
            playbackSpeed = 1.5f,
            backfillEnabled = false,
        )
        assertThat(o.autoDownload.apply(full, null).autoDownload).isNull()
        assertThat(o.keepLatest.apply(full, null).keepLatestCount).isNull()
        assertThat(o.deletePlayed.apply(full, null).deletePlayed).isNull()
        assertThat(o.maxAgeDays.apply(full, null).maxAgeDays).isNull()
        assertThat(o.allowCellular.apply(full, null).allowCellular).isNull()
        assertThat(o.sortOrder.apply(full, null).sortOrder).isNull()
        assertThat(o.playbackSpeed.apply(full, null).playbackSpeed).isNull()
        assertThat(o.backfill.apply(full, null).backfillEnabled).isNull()
    }

    @Test fun explicitChoicesWriteTheirValues() {
        assertThat(o.autoDownload.apply(blank, false).autoDownload).isFalse()
        assertThat(o.keepLatest.apply(blank, 15).keepLatestCount).isEqualTo(15)
        assertThat(o.maxAgeDays.apply(blank, 0).maxAgeDays).isEqualTo(0)
        assertThat(o.allowCellular.apply(blank, true).allowCellular).isTrue()
        assertThat(o.playbackSpeed.apply(blank, 1.8f).playbackSpeed).isEqualTo(1.8f)
    }

    @Test fun keepLatestSnapsUnofferedStoredValueToNearest() {
        val stored = blank.copy(keepLatestCount = 7)
        assertThat(o.keepLatest.selected(stored)).isEqualTo(5)
    }

    @Test fun skipIntroZeroIsOffAndStaysZero() {
        val off = o.skipIntro.apply(blank, 0)
        assertThat(off.skipIntroSec).isEqualTo(0)
        assertThat(o.skipIntro.label(off)).isEqualTo("Off")
        assertThat(o.skipIntro.label(blank)).isEqualTo("Off")
    }

    @Test fun skipIntroAndOutroLabelsAndOptionOrder() {
        assertThat(o.skipIntro.options.map { it.second })
            .containsExactly("Off", "15 s", "30 s", "45 s", "60 s", "90 s", "2 min").inOrder()
        assertThat(o.skipOutro.label(blank.copy(skipOutroSec = 120))).isEqualTo("2 min")
        assertThat(o.skipIntro.label(blank.copy(skipIntroSec = 20))).isEqualTo("15 s")
    }

    @Test fun skipOutroWritesOnlyOutroField() {
        val entity = o.skipOutro.apply(blank.copy(skipIntroSec = 30), 45)
        assertThat(entity.skipOutroSec).isEqualTo(45)
        assertThat(entity.skipIntroSec).isEqualTo(30)
    }

    @Test fun autoAddToQueueOffTopBottomLabelsAndWrites() {
        assertThat(o.autoAddToQueue.options.map { it.second }).containsExactly("Off", "Top", "Bottom").inOrder()
        assertThat(o.autoAddToQueue.label(blank)).isEqualTo("Off")
        assertThat(o.autoAddToQueue.apply(blank, QueueAdd.BOTTOM).autoAddToQueue).isEqualTo(QueueAdd.BOTTOM)
    }

    @Test fun notifyNewEpisodesSwitchWritesOnlyItsField() {
        val entity = o.applyNotifyNewEpisodes(blank.copy(autoDownload = true), false)
        assertThat(entity.notifyNewEpisodes).isFalse()
        assertThat(entity.autoDownload).isTrue()
    }

    @Test fun playbackSpeedDefaultLabelShowsGlobalSpeedAndSnapsStoredValue() {
        assertThat(o.playbackSpeed.label(blank, episodic)).isEqualTo("Default (1.0×)")
        assertThat(o.playbackSpeed.label(blank.copy(playbackSpeed = 1.5f), episodic)).isEqualTo("1.5×")
        assertThat(o.playbackSpeed.selected(blank.copy(playbackSpeed = 1.45f))).isEqualTo(1.5f)
    }

    @Test fun backfillDefaultLabelFollowsGlobalSwitch() {
        assertThat(o.backfill.label(blank, episodic)).isEqualTo("Default (On)")
        val off = Inheritance(GlobalSettings(backfillEnabled = false), FeedType.EPISODIC)
        assertThat(o.backfill.label(blank.copy(backfillEnabled = true), off)).isEqualTo("On")
    }

    @Test fun deletePlayedAndDownloadOptionsListDefaultFirst() {
        assertThat(o.deletePlayed.options(episodic).map { it.first }).containsExactly(null, true, false).inOrder()
        assertThat(o.allowCellular.options(episodic).map { it.second })
            .containsExactly("Default (Wi-Fi only)", "Wi-Fi only", "Wi-Fi and mobile data").inOrder()
    }

    @Test fun maxAgeOptionsAreTheSpecLabels() {
        assertThat(o.maxAgeDays.options(episodic).map { it.second })
            .containsExactly("Default (1 month)", "1 week", "2 weeks", "1 month", "3 months", "Never").inOrder()
    }
}
