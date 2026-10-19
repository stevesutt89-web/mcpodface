package com.steve.mcpodface.data.backup

import com.google.common.truth.Truth.assertThat
import com.steve.mcpodface.data.feed.SubscriptionRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OpmlImporterTest {
    private val repo = mockk<SubscriptionRepository>()

    private fun feeds(n: Int, prefix: String = "https://feed.example/") =
        (1..n).map { OpmlFeed("$prefix$it.xml", "Show $it") }

    private fun stubSubscribe() {
        coEvery { repo.subscribe(any()) } just Runs
    }

    @Test fun allFeedsSucceedAndAreSubscribed() = runTest {
        coEvery { repo.open(any(), any(), any(), any()) } returns SubscriptionRepository.OpenResult.Ok(1L)
        stubSubscribe()
        val importer = OpmlImporter(repo, this)

        importer.start(feeds(5))
        advanceUntilIdle()

        val p = importer.progress.value!!
        assertThat(p.running).isFalse()
        assertThat(p.total).isEqualTo(5)
        assertThat(p.done).isEqualTo(5)
        assertThat(p.succeeded).isEqualTo(5)
        assertThat(p.failed).isEmpty()
        coVerify(exactly = 5) { repo.subscribe(1L) }
    }

    @Test fun failedFeedsAreRecordedWithTheirReasonsAndNotSubscribed() = runTest {
        // mockk matches the most recently declared stub, so the catch-all goes first.
        coEvery { repo.open(any(), any(), any(), any()) } returns SubscriptionRepository.OpenResult.Ok(7L)
        coEvery { repo.open("https://feed.example/bad1.xml", any(), any(), any()) } returns
            SubscriptionRepository.OpenResult.Failed("HTTP 404")
        coEvery { repo.open("https://feed.example/bad2.xml", any(), any(), any()) } returns
            SubscriptionRepository.OpenResult.Failed("Not a feed")
        stubSubscribe()
        val importer = OpmlImporter(repo, this)
        val input = listOf(
            OpmlFeed("https://feed.example/ok.xml", "Ok"),
            OpmlFeed("https://feed.example/bad1.xml", null),
            OpmlFeed("https://feed.example/bad2.xml", null),
        )

        importer.start(input)
        advanceUntilIdle()

        val p = importer.progress.value!!
        assertThat(p.done).isEqualTo(3)
        assertThat(p.succeeded).isEqualTo(1)
        assertThat(p.failed).containsExactly(
            "https://feed.example/bad1.xml" to "HTTP 404",
            "https://feed.example/bad2.xml" to "Not a feed",
        )
        coVerify(exactly = 1) { repo.subscribe(7L) }
    }

    @Test fun exceptionFromOpenIsRecordedAsAFailure() = runTest {
        coEvery { repo.open(any(), any(), any(), any()) } throws IllegalStateException("disk full")
        val importer = OpmlImporter(repo, this)

        importer.start(feeds(1))
        advanceUntilIdle()

        val p = importer.progress.value!!
        assertThat(p.done).isEqualTo(1)
        assertThat(p.failed).containsExactly("https://feed.example/1.xml" to "disk full")
    }

    @Test fun concurrencyNeverExceedsFour() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        coEvery { repo.open(any(), any(), any(), any()) } coAnswers {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(100)
            } finally {
                inFlight--
            }
            SubscriptionRepository.OpenResult.Ok(1L)
        }
        stubSubscribe()
        val importer = OpmlImporter(repo, this)

        importer.start(feeds(20))
        advanceUntilIdle()

        assertThat(maxInFlight).isEqualTo(OpmlImporter.MAX_CONCURRENT)
        assertThat(inFlight).isEqualTo(0)
        assertThat(importer.progress.value!!.done).isEqualTo(20)
    }

    @Test fun secondStartWhileRunningIsIgnored() = runTest {
        coEvery { repo.open(any(), any(), any(), any()) } coAnswers {
            delay(50)
            SubscriptionRepository.OpenResult.Ok(1L)
        }
        stubSubscribe()
        val importer = OpmlImporter(repo, this)

        importer.start(feeds(3))
        importer.start(feeds(10, prefix = "https://other.example/"))
        assertThat(importer.progress.value!!.total).isEqualTo(3)
        advanceUntilIdle()

        val p = importer.progress.value!!
        assertThat(p.total).isEqualTo(3)
        assertThat(p.done).isEqualTo(3)
        coVerify(exactly = 3) { repo.open(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.open(match { it.startsWith("https://other.example/") }, any(), any(), any()) }
    }

    @Test fun progressIsRunningUntilEveryFeedIsDone() = runTest {
        coEvery { repo.open(any(), any(), any(), any()) } coAnswers {
            delay(10)
            SubscriptionRepository.OpenResult.Ok(1L)
        }
        stubSubscribe()
        val importer = OpmlImporter(repo, this)

        importer.start(feeds(8))
        val started = importer.progress.value!!
        assertThat(started.running).isTrue()
        assertThat(started.done).isEqualTo(0)

        advanceTimeBy(15) // first wave of 4 has finished, the second is in flight
        val midway = importer.progress.value!!
        assertThat(midway.running).isTrue()
        assertThat(midway.done).isEqualTo(4)
        assertThat(midway.succeeded).isEqualTo(4)

        advanceUntilIdle()
        val finished = importer.progress.value!!
        assertThat(finished.running).isFalse()
        assertThat(finished.done).isEqualTo(8)
    }

    @Test fun dismissResultClearsFinishedImportButNotRunningOne() = runTest {
        coEvery { repo.open(any(), any(), any(), any()) } coAnswers {
            delay(10)
            SubscriptionRepository.OpenResult.Ok(1L)
        }
        stubSubscribe()
        val importer = OpmlImporter(repo, this)

        importer.start(feeds(2))
        importer.dismissResult()
        assertThat(importer.progress.value?.running).isTrue()

        advanceUntilIdle()
        importer.dismissResult()
        assertThat(importer.progress.value).isNull()
    }

    @Test fun emptyListFinishesImmediatelyWithZeroTotal() = runTest {
        val importer = OpmlImporter(repo, this)

        importer.start(emptyList())
        advanceUntilIdle()

        val p = importer.progress.value!!
        assertThat(p.running).isFalse()
        assertThat(p.total).isEqualTo(0)
    }
}
