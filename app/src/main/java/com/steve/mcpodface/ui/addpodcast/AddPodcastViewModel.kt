package com.steve.mcpodface.ui.addpodcast

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steve.mcpodface.core.di.IoDispatcher
import com.steve.mcpodface.data.backup.Opml
import com.steve.mcpodface.data.backup.OpmlExporter
import com.steve.mcpodface.data.backup.OpmlImportProgress
import com.steve.mcpodface.data.backup.OpmlImporter
import com.steve.mcpodface.data.backup.OpmlParseException
import com.steve.mcpodface.data.feed.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

sealed interface AddUrlState {
    data object Idle : AddUrlState
    data object Working : AddUrlState
    data class Failed(val message: String) : AddUrlState
}

sealed interface AddPodcastEvent {
    data class OpenPodcast(val podcastId: Long) : AddPodcastEvent
    data class Message(val text: String) : AddPodcastEvent
}

@HiltViewModel
class AddPodcastViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val importer: OpmlImporter,
    private val exporter: OpmlExporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _addUrl = MutableStateFlow<AddUrlState>(AddUrlState.Idle)
    val addUrl: StateFlow<AddUrlState> = _addUrl.asStateFlow()

    /** Import progress; null when there is nothing to show. */
    val importProgress: StateFlow<OpmlImportProgress?> = importer.progress

    /** True after "Hide" is tapped on a running import; reset when a new import starts. */
    private val _importHidden = MutableStateFlow(false)
    val importHidden: StateFlow<Boolean> = _importHidden.asStateFlow()

    private val _events = Channel<AddPodcastEvent>(Channel.BUFFERED)
    val events: Flow<AddPodcastEvent> = _events.receiveAsFlow()

    /** Adds one feed by URL (no hint; basic-auth credentials only for private feeds). */
    fun addByUrl(url: String, authUser: String?, authPassword: String?) {
        if (_addUrl.value == AddUrlState.Working) return
        _addUrl.value = AddUrlState.Working
        viewModelScope.launch {
            val failure = try {
                when (val result = subscriptions.open(url, null, authUser, authPassword)) {
                    is SubscriptionRepository.OpenResult.Ok -> {
                        subscriptions.subscribe(result.podcastId)
                        _addUrl.value = AddUrlState.Idle
                        _events.send(AddPodcastEvent.OpenPodcast(result.podcastId))
                        null
                    }
                    is SubscriptionRepository.OpenResult.Failed -> result.message
                }
            } catch (e: Exception) {
                e.message ?: "Could not add this feed"
            }
            if (failure != null) _addUrl.update { AddUrlState.Failed(failure) }
        }
    }

    /** Called when the dialog closes, so a stale error is not shown the next time. */
    fun resetAddUrl() {
        if (_addUrl.value != AddUrlState.Working) _addUrl.value = AddUrlState.Idle
    }

    /** Reads an OPML file and starts the background import. */
    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            val feeds = try {
                withContext(io) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the file")
                    input.use { Opml.parse(it) }
                }
            } catch (e: OpmlParseException) {
                _events.send(AddPodcastEvent.Message(e.message ?: "Not a valid OPML file"))
                return@launch
            } catch (e: IOException) {
                _events.send(AddPodcastEvent.Message("Could not read the file"))
                return@launch
            }
            when {
                feeds.isEmpty() -> _events.send(AddPodcastEvent.Message("No podcast feeds found in that file"))
                importer.progress.value?.running == true ->
                    _events.send(AddPodcastEvent.Message("An import is already running"))
                else -> {
                    _importHidden.value = false
                    importer.start(feeds)
                }
            }
        }
    }

    fun hideImport() {
        _importHidden.value = true
    }

    fun dismissImportResult() {
        importer.dismissResult()
    }

    /** Writes all subscriptions to [uri] as OPML. */
    fun exportOpml(uri: Uri) {
        viewModelScope.launch {
            val message = try {
                val count = withContext(io) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Could not open the file")
                    out.use { exporter.export(it) }
                }
                "Exported $count ${if (count == 1) "podcast" else "podcasts"}"
            } catch (e: IOException) {
                "Export failed: ${e.message ?: "could not write the file"}"
            }
            _events.send(AddPodcastEvent.Message(message))
        }
    }
}
