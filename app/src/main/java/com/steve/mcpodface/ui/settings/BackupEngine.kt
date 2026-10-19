package com.steve.mcpodface.ui.settings

import android.content.Context
import android.net.Uri
import com.steve.mcpodface.data.backup.BackupExporter
import com.steve.mcpodface.data.backup.BackupImporter
import com.steve.mcpodface.data.backup.ImportMode
import com.steve.mcpodface.data.backup.ImportResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject

/** Moves the backup in and out of the files the user picked (PLAN.md §9). Wrapped so the ViewModel can be tested. */
interface BackupEngine {
    /** Writes a backup to [uri]. Progress is 0..1. */
    suspend fun export(uri: Uri, includeArchive: Boolean, onProgress: (Float) -> Unit)

    /** Restores the backup at [uri]. Progress is 0..1. */
    suspend fun import(uri: Uri, mode: ImportMode, onProgress: (Float) -> Unit): ImportResult
}

/** Opens the picked files through the content resolver and closes them itself. */
class ContentBackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
) : BackupEngine {
    override suspend fun export(uri: Uri, includeArchive: Boolean, onProgress: (Float) -> Unit) {
        val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Could not open the file")
        out.use { exporter.export(it, includeArchive, onProgress) }
    }

    override suspend fun import(uri: Uri, mode: ImportMode, onProgress: (Float) -> Unit): ImportResult {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
        return input.use { importer.import(it, mode, onProgress = onProgress) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupEngineModule {
    @Binds
    abstract fun bindBackupEngine(impl: ContentBackupEngine): BackupEngine
}
