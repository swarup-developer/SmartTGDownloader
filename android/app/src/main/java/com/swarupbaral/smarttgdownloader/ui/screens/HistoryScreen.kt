package com.swarupbaral.smarttgdownloader.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.DownloadHistoryEntity
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes the persisted download history to the UI. */
class HistoryViewModel : ViewModel() {

    private val allHistory = AppContainer.historyDao.observeAll()

    /** Active filter: matches an entry's category OR source title; null = all. */
    private val activeFilter = MutableStateFlow<String?>(null)

    val history: StateFlow<List<DownloadHistoryEntity>> =
        kotlinx.coroutines.flow.combine(allHistory, activeFilter) { list, filter ->
            if (filter == null) list
            else list.filter { it.category == filter || it.sourceTitle == filter }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun setFilter(value: String?) {
        activeFilter.value = value
    }

    /** Unfiltered list — the filter chips must stay visible while filtering. */
    val allEntries: StateFlow<List<DownloadHistoryEntity>> = allHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /** Message ids with a re-download currently in flight. */
    private val _reDownloading = MutableStateFlow<Set<Long>>(emptySet())
    val reDownloading: StateFlow<Set<Long>> = _reDownloading.asStateFlow()

    fun delete(entry: DownloadHistoryEntity) {
        viewModelScope.launch { AppContainer.historyDao.deleteByMessageId(entry.messageId) }
    }

    fun clearAll() {
        viewModelScope.launch { AppContainer.historyDao.clearAll() }
    }

    /** The downloaded file if it still exists on disk, else null. */
    fun fileOf(entry: DownloadHistoryEntity): File? {
        val path = entry.localPath ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Opens a completed download in an external app via FileProvider. */
    fun openFile(context: Context, entry: DownloadHistoryEntity) {
        val file = fileOf(entry) ?: return
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(entry))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Re-fetches the message from Telegram and downloads it again into the
     * same location, refreshing the row on success.
     */
    fun reDownload(context: Context, entry: DownloadHistoryEntity) {
        if (entry.messageId in _reDownloading.value) return
        _reDownloading.value = _reDownloading.value + entry.messageId
        viewModelScope.launch {
            try {
                val message = AppContainer.repository.getMessage(entry.chatId, entry.messageId)
                if (message == null) {
                    Toast.makeText(
                        context,
                        "Message is no longer available in the channel.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val path = AppContainer.repository.downloadFile(
                    message = message,
                    destinationDir = localDirFor(context, entry)
                )
                AppContainer.historyDao.upsert(
                    entry.copy(
                        localPath = path,
                        succeeded = true,
                        completedAtEpochMs = System.currentTimeMillis()
                    )
                )
                Toast.makeText(context, "Downloaded ${entry.fileName}", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.message ?: "Re-download failed.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                _reDownloading.value = _reDownloading.value - entry.messageId
            }
        }
    }

    private fun localDirFor(context: Context, entry: DownloadHistoryEntity): File {
        // Must mirror DownloadViewModel's location: Download/SmartTG/<channel>/<category>/.
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SmartTG/${entry.sourceTitle}"
        )
        return File(base, entry.category)
    }

    private fun mimeFor(entry: DownloadHistoryEntity): String {
        val ext = entry.fileName.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (entry.category) {
            "Audio" -> "audio/*"
            "Video" -> "video/*"
            "Images" -> "image/*"
            else -> "application/octet-stream"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel()
) {
    val history by viewModel.history.collectAsState()
    val allEntries by viewModel.allEntries.collectAsState()
    val reDownloading by viewModel.reDownloading.collectAsState()
    val context = LocalContext.current
    var pendingClear by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf<String?>(null) }

    // Distinct filter values from the UNFILTERED history: categories first,
    // then source titles, so chips remain available while a filter is active.
    val categories = allEntries.map { it.category }.distinct()
    val sources = allEntries.map { it.sourceTitle }.distinct()
    val filterValues = categories + sources

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Download history",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = { pendingClear = true }) { Text("Clear all") }
                }
            }

            if (filterValues.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeFilter == null,
                        onClick = {
                            activeFilter = null
                            viewModel.setFilter(null)
                        },
                        label = { Text("All") },
                        modifier = Modifier.semantics {
                            stateDescription = "No filter applied"
                        }
                    )
                    for (value in filterValues) {
                        FilterChip(
                            selected = activeFilter == value,
                            onClick = {
                                activeFilter = if (activeFilter == value) null else value
                                viewModel.setFilter(activeFilter)
                            },
                            label = { Text(value) },
                            modifier = Modifier.semantics {
                                stateDescription =
                                    if (activeFilter == value) "Filtered" else "Tap to filter"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No downloads yet.\nCompleted downloads will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(history, key = { it.messageId }) { entry ->
                        // Re-evaluated when the row's timestamp changes (e.g. after
                        // a re-download) so the Open action tracks file existence.
                        val fileExists = remember(entry.completedAtEpochMs, entry.localPath) {
                            viewModel.fileOf(entry) != null
                        }
                        HistoryRow(
                            entry = entry,
                            isRedownloading = entry.messageId in reDownloading,
                            fileExists = fileExists,
                            onDelete = { viewModel.delete(entry) },
                            onOpen = { viewModel.openFile(context, entry) },
                            onReDownload = { viewModel.reDownload(context, entry) }
                        )
                    }
                }
            }
        }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes all entries from the list. Downloaded files stay on disk.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAll()
                        pendingClear = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HistoryRow(
    entry: DownloadHistoryEntity,
    isRedownloading: Boolean,
    fileExists: Boolean,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onReDownload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.succeeded) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (entry.succeeded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = if (entry.succeeded) "Completed" else "Failed" }
            ) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${entry.sourceTitle} · ${entry.category} · ${formatBytes(entry.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(entry.completedAtEpochMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            if (isRedownloading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "Re-downloading ${entry.fileName}" },
                    strokeWidth = 2.dp
                )
            } else {
                if (entry.succeeded && fileExists) {
                    IconButton(
                        onClick = onOpen,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "Open file")
                    }
                }
                if (entry.succeeded) {
                    IconButton(
                        onClick = onReDownload,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-download")
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return "%.2f %s".format(value, units[idx])
}
