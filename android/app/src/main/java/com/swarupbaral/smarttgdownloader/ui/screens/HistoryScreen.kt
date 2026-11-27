package com.swarupbaral.smarttgdownloader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.DownloadHistoryEntity
import com.swarupbaral.smarttgdownloader.data.DownloadManager
import com.swarupbaral.smarttgdownloader.data.ChannelDownloadSummary
import com.swarupbaral.smarttgdownloader.data.DownloadsStore
import com.swarupbaral.smarttgdownloader.data.NetworkGuard
import com.swarupbaral.smarttgdownloader.data.channelDownloadSummaries
import com.swarupbaral.smarttgdownloader.data.downloadedCountsByChannelFolder
import com.swarupbaral.smarttgdownloader.data.isArchive
import com.swarupbaral.smarttgdownloader.data.QueuedDownload
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes the persisted download history to the UI. */
class HistoryViewModel : ViewModel() {

    private val allHistory = AppContainer.historyDao.observeAll()

    /**
     * Active filter, always one of [AppPreferences.DOWNLOAD_CATEGORIES].
     *
     * Filtering is by category only. An earlier version also offered a chip per
     * channel name, which produced an unmanageable row of options — the filter
     * set is now identical to the one Settings offers, and nothing else.
     */
    private val activeFilter = MutableStateFlow(AppPreferences.CATEGORY_ALL)

    val history: StateFlow<List<DownloadHistoryEntity>> =
        combine(allHistory, activeFilter) { list, filter ->
            if (filter == AppPreferences.CATEGORY_ALL) list
            else list.filter { it.category == filter }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun setFilter(value: String) {
        activeFilter.value = value
    }

    /** Unfiltered list — the filter chips must stay visible while filtering. */
    val allEntries: StateFlow<List<DownloadHistoryEntity>> = allHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * Message ids queued or in flight, so rows can show a spinner.
     *
     * Sourced from the engine rather than tracked locally: the download may have
     * been started from Home, or may have outlived this screen entirely.
     */
    val reDownloading: StateFlow<Set<Long>> = DownloadManager.pendingIds

    fun delete(entry: DownloadHistoryEntity) {
        viewModelScope.launch { AppContainer.historyDao.deleteByMessageId(entry.messageId) }
    }

    fun clearAll() {
        viewModelScope.launch { AppContainer.historyDao.clearAll() }
    }

    /**
     * True when the entry's file is still present where it was saved.
     *
     * Suspend + IO on purpose: for a MediaStore URI this is an IPC round-trip
     * to the media provider, which must never run while the history list is
     * being measured on the main thread.
     */
    suspend fun fileExists(context: Context, entry: DownloadHistoryEntity): Boolean {
        val location = entry.localPath ?: return false
        return withContext(Dispatchers.IO) { DownloadsStore.exists(context, location) }
    }

    /**
     * Opens a completed download.
     *
     * Files live in the user's Download folder, reached either by MediaStore
     * `content://` URI (Android 10+) or by path (older devices, or the
     * app-private fallback), so both forms have to be handled.
     */
    fun openFile(context: Context, entry: DownloadHistoryEntity) {
        val location = entry.localPath ?: return
        val mime = DownloadsStore.mimeFor(entry.fileName, entry.category)
        val intent = if (location.startsWith("content://")) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(location), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val file = File(location)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Re-fetches the message from Telegram and saves it again.
     *
     * Queued through [DownloadManager] like any other download, so it survives
     * this screen being closed, writes its own history row, and resumes after a
     * process death instead of being lost mid-flight.
     */
    fun reDownload(context: Context, entry: DownloadHistoryEntity) {
        // An archive was built on this device out of files that were fetched
        // once; there is no message to fetch again, so Re-download is neither
        // offered nor acted on. Only the UI hiding the button would be a promise
        // that the rest of the app does not keep.
        if (entry.isArchive) return
        if (entry.messageId in DownloadManager.pendingIds.value) return
        // Same connection rule as a fresh download.
        NetworkGuard.blockedReason(
            context,
            AppContainer.appPreferences.downloadNetwork.value
        )?.let { blocked ->
            Toast.makeText(context, blocked, Toast.LENGTH_LONG).show()
            return
        }
        DownloadManager.enqueue(
            context = context.applicationContext,
            items = listOf(
                QueuedDownload(
                    messageId = entry.messageId,
                    // A history row has no TDLib file id; the engine still marks
                    // the row complete from the bytes it actually fetched.
                    fileId = 0,
                    chatId = entry.chatId,
                    fileName = entry.fileName,
                    category = entry.category,
                    sourceTitle = entry.sourceTitle,
                    sizeBytes = entry.sizeBytes
                )
            )
        )
        Toast.makeText(context, "Re-download started", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenMenu: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val history by viewModel.history.collectAsState()
    val allEntries by viewModel.allEntries.collectAsState()
    val reDownloading by viewModel.reDownloading.collectAsState()
    val context = LocalContext.current
    // Built from the *unfiltered* list on purpose: a category filter narrows what
    // is listed, and should not make a channel look emptier than it is. Both the
    // cards and the per-row counts come from this one computation.
    val channelSummaries = remember(allEntries) { channelDownloadSummaries(allEntries) }
    // Both read the same rule — the count is derived from the summaries — so a row
    // and the card above it can never disagree about how many files a channel has.
    val channelCounts = remember(allEntries) { downloadedCountsByChannelFolder(allEntries) }
    var pendingClear by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(AppPreferences.CATEGORY_ALL) }
    // Set only when the device has no file manager to show the folder, so the
    // user is told where the files are instead of getting a dead button.
    var folderMessage by remember { mutableStateOf<String?>(null) }

    // One way in for both folder buttons: the outcome is about the device, not
    // about which folder was asked for.
    val openFolder: (String?) -> Unit = { folder ->
        folderMessage = when (DownloadsStore.openFolder(context, folder)) {
            DownloadsStore.FolderOpenOutcome.OPENED -> null
            DownloadsStore.FolderOpenOutcome.NO_APP ->
                "No file manager app found on this device. The files are in " +
                    "${DownloadsStore.displayPath(folder ?: DownloadsStore.ROOT)}."
        }
    }

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
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Open menu")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                // "History", the same word the tab and the menu use. The screen
                // said "Download history" while both of those said "History".
                "History",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            if (allEntries.isNotEmpty()) {
                TextButton(onClick = { pendingClear = true }) { Text("Clear all") }
            }
        }

        // The entries point at files in the Download folder; this is the way to
        // go and look at them without leaving the app to hunt through Files.
        if (allEntries.isNotEmpty()) {
            OutlinedButton(
                onClick = { openFolder(null) },
                modifier = Modifier
                    // Full width, like every other action button in the app: an
                    // action that wraps its own text is the odd one out, and a
                    // wider target is easier to hit either way.
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "Open Downloads folder, ${DownloadsStore.displayPath()}"
                    }
            ) {
                Text("Open Downloads folder")
            }
            folderMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (allEntries.isNotEmpty()) {
            Text(
                "Filter",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Exactly the Settings filter set — no per-channel chips.
                for (category in AppPreferences.DOWNLOAD_CATEGORIES) {
                    val isSelected = activeFilter == category
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            activeFilter = category
                            viewModel.setFilter(category)
                        },
                        label = { Text(category) },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            stateDescription = if (isSelected) "Selected" else "Not selected"
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            allEntries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No downloads yet.\nCompleted downloads will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            history.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No \"$activeFilter\" downloads yet.\nChoose \"${AppPreferences.CATEGORY_ALL}\" to see everything.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // The channel summaries scroll with the list rather than
                    // sitting in a fixed block above it: a long history still
                    // gets the whole screen for the downloads themselves.
                    if (channelSummaries.isNotEmpty()) {
                        item(key = "channels-heading") {
                            Column {
                                Text(
                                    "Channels",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.semantics { heading() }
                                )
                                Text(
                                    "One card per channel, with everything downloaded " +
                                        "from it so far.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        items(channelSummaries, key = { "channel-${it.folder}" }) { summary ->
                            ChannelSummaryCard(
                                summary = summary,
                                onOpenFolder = { openFolder(summary.folder) }
                            )
                        }
                        item(key = "channels-gap") { Spacer(Modifier.height(4.dp)) }
                    }

                    items(history, key = { it.messageId }) { entry ->
                        // Probed asynchronously and re-run when the row's
                        // timestamp changes (e.g. after a re-download), so the
                        // Open action tracks file existence without blocking
                        // the frame. The action stays hidden until the probe
                        // confirms the file is really there.
                        var exists by remember(
                            entry.messageId,
                            entry.completedAtEpochMs,
                            entry.localPath
                        ) {
                            mutableStateOf(false)
                        }
                        LaunchedEffect(
                            entry.messageId,
                            entry.completedAtEpochMs,
                            entry.localPath
                        ) {
                            exists = viewModel.fileExists(context, entry)
                        }
                        val channelFolder = DownloadsStore.channelFolder(entry.sourceTitle)
                        HistoryRow(
                            entry = entry,
                            isRedownloading = entry.messageId in reDownloading,
                            fileExists = exists,
                            channelFolder = channelFolder,
                            channelDownloadCount = channelCounts[channelFolder] ?: 0,
                            onDelete = { viewModel.delete(entry) },
                            onOpen = { viewModel.openFile(context, entry) },
                            onOpenChannelFolder = { openFolder(channelFolder) },
                            onReDownload = { viewModel.reDownload(context, entry) }
                        )
                    }
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
    channelFolder: String,
    channelDownloadCount: Int,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenChannelFolder: () -> Unit,
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
                imageVector = when {
                    !entry.succeeded -> Icons.Filled.Error
                    // A zip is shown as a zip at a glance, not only in words.
                    entry.isArchive -> Icons.Filled.FolderZip
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = if (entry.succeeded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = when {
                            !entry.succeeded -> "Failed"
                            entry.isArchive -> "Completed, ZIP archive"
                            else -> "Completed"
                        }
                    }
            ) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The channel name is the way into that channel's folder in
                // Downloads, because the channel is what owns the folder — so
                // the name a person already reads is the thing they can tap,
                // and the count is right next to it. The folder name is used
                // rather than the raw title because that is what is on disk.
                val folderName = DownloadsStore.channelName(channelFolder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channelDownloadCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                // fill = false so a long channel name is capped at
                                // what is left after the type and size, instead of
                                // squeezing them out of the row entirely.
                                .weight(1f, fill = false)
                                .clickable(role = Role.Button, onClick = onOpenChannelFolder)
                                .semantics {
                                    // Spoken instead of the label below: the
                                    // visible text is the short form, this is
                                    // the full question and answer.
                                    contentDescription = "Open $folderName folder, " +
                                        countPhrase(channelDownloadCount)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                "$folderName (${countLabel(channelDownloadCount)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else {
                        Text(
                            entry.sourceTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Measured at its own size, never weighted: the type and size
                    // are the short facts that must survive a long channel name.
                    // An archive states its kind here instead of its type: "ZIP"
                    // is what the row is, and the packed type is still what the
                    // filter above selects it by.
                    Text(
                        if (entry.isArchive) "ZIP · ${formatBytes(entry.sizeBytes)}"
                        else "${entry.category} · ${formatBytes(entry.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = if (entry.isArchive) {
                                "Open ZIP ${entry.fileName}"
                            } else {
                                "Open file"
                            }
                        )
                    }
                }
                // Offered for failed rows too: retrying is exactly what a
                // failed download needs, and it goes through the same engine
                // (so it respects the Wi-Fi-only setting and survives the
                // screen being closed).
                //
                // Not for an archive: it was built here from files that were
                // fetched once, so there is nothing to fetch again — the row
                // carries Open and Delete and nothing else.
                if (!entry.isArchive) {
                    IconButton(
                        onClick = onReDownload,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = if (entry.succeeded) {
                                "Re-download ${entry.fileName}"
                            } else {
                                "Retry ${entry.fileName}"
                            }
                        )
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

/** "12 files" — short form, for the row label. */
private fun countLabel(count: Int): String =
    if (count == 1) "1 file" else "$count files"

/** "12 files downloaded from this channel" — the sentence a screen reader hears. */
private fun countPhrase(count: Int): String =
    if (count == 1) "1 file downloaded from this channel"
    else "$count files downloaded from this channel"

/**
 * One channel's summary: what is in its folder, and the way in.
 *
 * The count and size belong to the channel rather than to any single row, so they
 * are stated once here as well as on each entry that links to the folder.
 */
@Composable
private fun ChannelSummaryCard(
    summary: ChannelDownloadSummary,
    onOpenFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    channelSummaryLine(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // "Open folder" is enough on screen, where the card's own title says
            // which folder; the spoken label says it anyway, since a screen reader
            // user may reach the button without the title in mind.
            OutlinedButton(
                onClick = onOpenFolder,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Open ${DownloadsStore.channelName(summary.folder)} folder, " +
                            "${channelSummaryLine(summary)}, " +
                            DownloadsStore.displayPath(summary.folder)
                }
            ) {
                Text("Open folder")
            }
        }
    }
}

/** "12 files · 2.4 GB", or "… · 2.4 GB or more" when Telegram withheld a size. */
private fun channelSummaryLine(summary: ChannelDownloadSummary): String {
    val files = if (summary.files == 1) "1 file" else "${summary.files} files"
    val size = formatBytes(summary.bytes)
    return if (summary.unknownSizes > 0) "$files · $size or more" else "$files · $size"
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
