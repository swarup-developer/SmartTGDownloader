package com.swarupbaral.smarttgdownloader.ui.screens

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.DownloadHistoryEntity
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the analyze → select → download pipeline.
 *
 * Scan tokens: every Analyze press mints a monotonically increasing token.
 * A new token immediately invalidates any in-flight scan, so results from a
 * previous link can never repopulate the results sheet — the Android mirror
 * of the stale-cache bug fixed on the Windows side.
 */
class DownloadViewModel : ViewModel() {

    data class FileProgress(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else 0f

        val isDone: Boolean
            get() = totalBytes > 0 && downloadedBytes >= totalBytes
    }

    data class UiState(
        val link: String = "",
        val analyzing: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val scanResult: TelegramRepository.ScanResult? = null,
        val selected: Set<Long> = emptySet(),
        val downloading: Boolean = false,
        val progressText: String? = null,
        /** Live per-file progress keyed by the scanned row's messageId. */
        val fileProgress: Map<Long, FileProgress> = emptyMap()
    )

    private val repository = AppContainer.repository

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var scanJob: Job? = null
    private var scanToken: Long = 0L
    private var downloadJob: Job? = null

    /** TDLib fileId → scanned row's messageId, rebuilt on every analyze. */
    private val messageIdsByFileId = mutableMapOf<Int, Long>()

    init {
        // Collapse the repository's UpdateFile-derived stream into per-row
        // progress keyed by messageId, so the results sheet can render a live
        // bar per file. TDLib file ids are matched to scan rows via the raw
        // TdApi.File captured in ScanRegistry.
        viewModelScope.launch {
            repository.downloadProgress.collect { progress ->
                val messageId = messageIdsByFileId[progress.fileId]
                if (messageId == null) return@collect
                _uiState.value = _uiState.value.copy(
                    fileProgress = _uiState.value.fileProgress + (
                        messageId to FileProgress(
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes
                        )
                        )
                )
            }
        }
    }

    fun updateLink(value: String) {
        _uiState.value = _uiState.value.copy(link = value)
    }

    fun analyze() {
        val link = _uiState.value.link.trim()
        if (link.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Telegram link is required.")
            return
        }

        val token = ++scanToken
        scanJob?.cancel()
        messageIdsByFileId.clear()
        _uiState.value = _uiState.value.copy(
            analyzing = true,
            errorMessage = null,
            statusMessage = "Analyzing link…",
            scanResult = null,
            selected = emptySet(),
            fileProgress = emptyMap()
        )

        scanJob = viewModelScope.launch {
            try {
                val result = repository.scanLink(
                    link = link,
                    categories = setOf("Audio", "Video", "Images", "Docs"),
                    scanToken = token
                )
                if (token != scanToken) return@launch // superseded by a newer scan

                // Route TDLib UpdateFile progress to the right results-sheet row.
                messageIdsByFileId.clear()
                for ((messageId, message) in repository.messagesForToken(token)) {
                    repository.fileIdOf(message)?.let { fileId ->
                        messageIdsByFileId[fileId] = messageId
                    }
                }

                // Pre-select files according to the user's default category.
                val defaultCategory = AppContainer.appPreferences.defaultCategory.value
                val preselected =
                    if (defaultCategory == AppPreferences.CATEGORY_ALL ||
                        defaultCategory.isBlank()
                    ) {
                        result.files.map { it.messageId }
                    } else {
                        result.files.filter { it.category == defaultCategory }
                            .map { it.messageId }
                    }

                _uiState.value = _uiState.value.copy(
                    analyzing = false,
                    statusMessage = null,
                    scanResult = result,
                    selected = preselected.toSet(),
                    fileProgress = emptyMap()
                )
                if (result.files.isEmpty()) {
                    _events.tryEmit("Scan complete with no matching files.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == scanToken) {
                    _uiState.value = _uiState.value.copy(
                        analyzing = false,
                        errorMessage = e.message ?: "Analysis failed."
                    )
                }
            }
        }
    }

    fun toggleSelection(messageId: Long) {
        val current = _uiState.value
        val next = current.selected.toMutableSet()
        if (!next.add(messageId)) {
            next.remove(messageId)
        }
        _uiState.value = current.copy(selected = next)
    }

    fun selectAll() {
        val files = _uiState.value.scanResult?.files ?: return
        _uiState.value = _uiState.value.copy(selected = files.map { it.messageId }.toSet())
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selected = emptySet())
    }

    fun dismissResults() {
        messageIdsByFileId.clear()
        _uiState.value = _uiState.value.copy(
            scanResult = null,
            selected = emptySet(),
            fileProgress = emptyMap()
        )
    }

    fun startDownload(context: Context) {
        val state = _uiState.value
        val result = state.scanResult ?: return
        if (state.selected.isEmpty() || state.downloading) return

        // Resolve messages from the current scan token only — a stale token's
        // registry entry was already evicted by ScanRegistry.record().
        val chatId = repository.chatIdForToken(scanToken)
        val messages = repository.messagesForToken(scanToken)

        _uiState.value = state.copy(
            downloading = true,
            progressText = "Starting…",
            // Seed every selected row at 0% so bars appear immediately.
            fileProgress = result.files
                .filter { it.messageId in state.selected }
                .associate { it.messageId to FileProgress(0L, it.sizeBytes) }
        )
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                // Public, user-visible location: Download/SmartTG/<channel>/<category>/.
                // Requires all-files access (Android 11+) or legacy storage grants;
                // the PermissionsScreen gate guarantees this before downloads start.
                val baseDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SmartTG/${result.sourceTitle}"
                )
                var completed = 0
                var failed = 0
                for (file in result.files) {
                    if (file.messageId !in state.selected) continue
                    val message = messages[file.messageId]
                        ?: repository.getMessage(chatId, file.messageId)
                    if (message == null) {
                        failed++
                        recordHistory(chatId, result.sourceTitle, file, null)
                        continue
                    }
                    try {
                        val path = repository.downloadFile(
                            message = message,
                            destinationDir = File(baseDir, file.category)
                        )
                        recordHistory(chatId, result.sourceTitle, file, path)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        recordHistory(chatId, result.sourceTitle, file, null)
                    }
                    completed++
                    _uiState.value = _uiState.value.copy(
                        progressText = "Downloaded $completed/${state.selected.size}: ${file.name}"
                    )
                }
                _events.tryEmit(
                    if (failed == 0) "Download completed."
                    else "Finished with $failed failed file(s)."
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(e.message ?: "Download failed.")
            } finally {
                // Keep the final per-file percentages visible until the sheet is
                // dismissed; only clear the run-level text.
                _uiState.value = _uiState.value.copy(downloading = false, progressText = null)
            }
        }
    }

    /**
     * Persists a finished download attempt (success or failure) to Room so it
     * appears on the History tab and survives restarts. Keyed by messageId;
     * re-downloading the same message replaces its row with a fresh timestamp.
     */
    private suspend fun recordHistory(
        chatId: Long,
        sourceTitle: String,
        file: TelegramRepository.ScannedFile,
        localPath: String?
    ) {
        AppContainer.historyDao.upsert(
            DownloadHistoryEntity(
                messageId = file.messageId,
                chatId = chatId,
                fileName = file.name,
                category = file.category,
                sourceTitle = sourceTitle,
                sizeBytes = file.sizeBytes,
                completedAtEpochMs = System.currentTimeMillis(),
                localPath = localPath,
                succeeded = localPath != null
            )
        )
    }
}
