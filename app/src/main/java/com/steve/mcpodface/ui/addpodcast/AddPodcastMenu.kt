package com.steve.mcpodface.ui.addpodcast

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedRectangle
import com.steve.mcpodface.data.backup.OpmlImportProgress
import com.steve.mcpodface.ui.components.glass.GlassButton
import com.steve.mcpodface.ui.components.glass.GlassIconButton
import com.steve.mcpodface.ui.components.glass.GlassStyle
import com.steve.mcpodface.ui.components.glass.LocalGlassBackdrop
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews

private const val OPML_MIME_TYPE = "text/x-opml"
private val IMPORT_MIME_TYPES = arrayOf("text/x-opml", "text/xml", "application/xml", "*/*")
private const val EXPORT_FILE_NAME = "mcpodface-subscriptions.opml"

/**
 * Top-bar action on the Search tab: "Add by URL", "Import OPML", "Export OPML" (PLAN.md §8.9).
 * Opens the podcast after a successful add-by-URL.
 */
@Composable
fun AddPodcastMenu(onOpenPodcast: (Long) -> Unit) {
    val viewModel: AddPodcastViewModel = hiltViewModel()
    val addUrl by viewModel.addUrl.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importHidden by viewModel.importHidden.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val currentOnOpenPodcast by rememberUpdatedState(onOpenPodcast)

    var menuOpen by remember { mutableStateOf(false) }
    var showAddByUrl by rememberSaveable { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importOpml(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OPML_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.exportOpml(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AddPodcastEvent.OpenPodcast -> {
                    showAddByUrl = false
                    viewModel.resetAddUrl()
                    currentOnOpenPodcast(event.podcastId)
                }
                is AddPodcastEvent.Message ->
                    Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    Box {
        GlassIconButton(
            icon = Icons.Rounded.Add,
            contentDescription = "Add podcast",
            onClick = { menuOpen = true },
            size = 40.dp,
            iconSize = 20.dp,
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedRectangle(20.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Add by URL") },
                onClick = {
                    menuOpen = false
                    showAddByUrl = true
                },
            )
            DropdownMenuItem(
                text = { Text("Import OPML") },
                onClick = {
                    menuOpen = false
                    importPicker.launchOrToast(IMPORT_MIME_TYPES, context)
                },
            )
            DropdownMenuItem(
                text = { Text("Export OPML") },
                onClick = {
                    menuOpen = false
                    exportPicker.launchOrToast(EXPORT_FILE_NAME, context)
                },
            )
        }
    }

    if (showAddByUrl) {
        AddByUrlDialog(
            working = addUrl == AddUrlState.Working,
            error = (addUrl as? AddUrlState.Failed)?.message,
            onDismiss = {
                showAddByUrl = false
                viewModel.resetAddUrl()
            },
            onAdd = { url, user, password -> viewModel.addByUrl(url, user, password) },
        )
    }

    val progress = importProgress
    if (progress != null) {
        if (progress.running) {
            if (!importHidden) {
                ImportProgressDialog(progress = progress, onHide = viewModel::hideImport)
            }
        } else {
            GlassDialogScope {
                ImportResultDialog(progress = progress, onDismiss = viewModel::dismissImportResult)
            }
        }
    }
}

@Composable
internal fun ImportProgressDialog(progress: OpmlImportProgress, onHide: () -> Unit) {
    GlassDialogScope {
        AlertDialog(
            onDismissRequest = onHide,
            shape = RoundedRectangle(28.dp),
            title = { Text("Importing subscriptions") },
            text = { ImportProgressContent(progress) },
            confirmButton = { GlassButton(onClick = onHide) { Text("Hide") } },
        )
    }
}

/**
 * Dialogs open in their own window, so they must not sample the page's glass backdrop: this
 * drops the backdrop so glass inside falls back to its translucent fill.
 */
@Composable
internal fun GlassDialogScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBackdrop provides null, content = content)
}

/** The import progress body: "Importing 12 of 87…" with a bar. The import keeps running if hidden. */
@Composable
internal fun ImportProgressContent(progress: OpmlImportProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            "Importing ${progress.done} of ${progress.total}…",
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth(),
        )
        if (progress.failed.isNotEmpty()) {
            Text(
                "${progress.failed.size} failed so far",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun ImportResultDialog(progress: OpmlImportProgress, onDismiss: () -> Unit) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedRectangle(28.dp),
        title = { Text("Import finished") },
        text = {
            Column {
                Text(
                    ImportSummary.text(progress.succeeded, progress.failed.size),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (progress.failed.isNotEmpty()) {
                    GlassButton(onClick = { detailsOpen = !detailsOpen }) {
                        Text(if (detailsOpen) "Hide details" else "Details")
                    }
                    if (detailsOpen) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            progress.failed.forEach { (url, reason) ->
                                Text(url, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(Spacing.s))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { GlassButton(onClick = onDismiss, style = GlassStyle.Prominent) { Text("Done") } },
    )
}

/** "Subscribed to 85 podcasts. 2 failed." (the failure sentence is omitted when there are none). */
internal object ImportSummary {
    fun text(succeeded: Int, failed: Int): String {
        val noun = if (succeeded == 1) "podcast" else "podcasts"
        val base = "Subscribed to $succeeded $noun."
        return if (failed == 0) base else "$base $failed failed."
    }
}

@ThemePreviews
@Composable
private fun ImportProgressPreview() {
    AppTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                ImportProgressContent(
                    OpmlImportProgress(total = 87, done = 12, succeeded = 11, failed = listOf("https://bad.example/feed" to "HTTP 404"), running = true),
                )
            }
        }
    }
}

/** Opens a system file picker, telling the user instead of crashing when the phone has none. */
fun <I> ActivityResultLauncher<I>.launchOrToast(input: I, context: Context) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No file picker app found on this phone", Toast.LENGTH_LONG).show()
    }
}
