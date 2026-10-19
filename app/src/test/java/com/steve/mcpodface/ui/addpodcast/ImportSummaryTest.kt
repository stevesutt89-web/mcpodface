package com.steve.mcpodface.ui.addpodcast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportSummaryTest {
    @Test fun reportsSuccessesAndFailures() {
        assertThat(ImportSummary.text(85, 2)).isEqualTo("Subscribed to 85 podcasts. 2 failed.")
    }

    @Test fun omitsFailureSentenceWhenNothingFailed() {
        assertThat(ImportSummary.text(87, 0)).isEqualTo("Subscribed to 87 podcasts.")
    }

    @Test fun singularPodcast() {
        assertThat(ImportSummary.text(1, 0)).isEqualTo("Subscribed to 1 podcast.")
    }
}
