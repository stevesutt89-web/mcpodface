package com.steve.mcpodface.data.backup

import com.steve.mcpodface.core.di.ApplicationScope
import com.steve.mcpodface.data.feed.SubscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

data class OpmlImportProgress(
    val total: Int,
    val done: Int,
    val succeeded: Int,
    /** (feedUrl, reason) for every feed that could not be opened. */
    val failed: List<Pair<String, String>>,
    val running: Boolean,
)

/**
 * Subscribes to every feed in an OPML file (PLAN.md §9). Lives in the app scope so the import
 * continues when the screen that started it goes away; the UI observes [progress].
 */
@Singleton
class OpmlImporter @Inject constructor(
    private val subscriptions: SubscriptionRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<OpmlImportProgress?>(null)

    /** null when no import has run since the last [dismissResult]. */
    val progress: StateFlow<OpmlImportProgress?> = _progress.asStateFlow()

    private val lock = Any()

    /** Starts an import in the background. Ignored if an import is already running. */
    fun start(feeds: List<OpmlFeed>) {
        synchronized(lock) {
            if (_progress.value?.running == true) return
            _progress.value = OpmlImportProgress(
                total = feeds.size,
                done = 0,
                succeeded = 0,
                failed = emptyList(),
                running = true,
            )
        }
        scope.launch { run(feeds) }
    }

    /** Clears a finished result so it is not shown again. No-op while an import is running. */
    fun dismissResult() {
        synchronized(lock) {
            if (_progress.value?.running != true) _progress.value = null
        }
    }

    private suspend fun run(feeds: List<OpmlFeed>) {
        val permits = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            feeds.map { feed ->
                async {
                    permits.withPermit {
                        val failure = attempt(feed.feedUrl)
                        record(feed.feedUrl, failure)
                    }
                }
            }.awaitAll()
        }
        _progress.update { it?.copy(running = false) }
    }

    /** Returns null when the feed was subscribed, otherwise the reason it was not. */
    private suspend fun attempt(feedUrl: String): String? = try {
        when (val result = subscriptions.open(feedUrl, null, null, null)) {
            is SubscriptionRepository.OpenResult.Ok -> {
                subscriptions.subscribe(result.podcastId)
                null
            }
            is SubscriptionRepository.OpenResult.Failed -> result.message
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun record(feedUrl: String, failure: String?) {
        _progress.update { p ->
            when {
                p == null -> null
                failure == null -> p.copy(done = p.done + 1, succeeded = p.succeeded + 1)
                else -> p.copy(done = p.done + 1, failed = p.failed + (feedUrl to failure))
            }
        }
    }

    companion object {
        const val MAX_CONCURRENT = 4
    }
}
