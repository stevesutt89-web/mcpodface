package com.steve.mcpodface.ui.settings

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
import com.steve.mcpodface.data.prefs.GlobalSettings
import com.steve.mcpodface.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val importer: OpmlImporter,
    private val exporter: OpmlExporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val settings: StateFlow<GlobalSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    /** OPML import progress; null when there is nothing to show. */
    val importProgress: StateFlow<OpmlImportProgress?> = importer.progress

    /** True after "Hide" is tapped on a running import; reset when a new import starts. */
    private val _importHidden = MutableStateFlow(false)
    val importHidden: StateFlow<Boolean> = _importHidden.asStateFlow()

    /** One-line messages for the user (shown as toasts by the screen). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun update(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /** Reads an OPML file chosen with the Storage Access Framework and starts the background import. */
    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            val feeds = try {
                withContext(io) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open the file")
                    input.use { Opml.parse(it) }
                }
            } catch (e: OpmlParseException) {
                _messages.send(e.message ?: "Not a valid OPML file")
                return@launch
            } catch (e: IOException) {
                _messages.send("Could not read the file")
                return@launch
            }
            when {
                feeds.isEmpty() -> _messages.send("No podcast feeds found in that file")
                importer.progress.value?.running == true -> _messages.send("An import is already running")
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

    /** Writes all subscriptions to [uri] as OPML and reports the count. */
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
            _messages.send(message)
        }
    }
}
