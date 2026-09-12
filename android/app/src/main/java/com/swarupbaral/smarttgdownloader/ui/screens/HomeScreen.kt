package com.swarupbaral.smarttgdownloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Keep last-known per-file progress while the results sheet is open.
    val fileProgress = state.fileProgress

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TG Downloader",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Paste your Telegram link below:")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.link,
            onValueChange = viewModel::updateLink,
            placeholder = { Text("t.me/channel/123") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Telegram link input field" }
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = viewModel::analyze,
            enabled = !state.analyzing && state.link.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.analyzing) "Analyzing…" else "Analyze Link")
        }
        Spacer(Modifier.height(16.dp))

        state.statusMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        state.errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                    error(it)
                }
            )
        }
        state.progressText?.let {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (state.analyzing) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
        }
    }

    state.scanResult?.let { result ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissResults) {
            ScanResultsContent(
                result = result,
                selected = state.selected,
                downloading = state.downloading,
                fileProgress = fileProgress,
                onToggle = viewModel::toggleSelection,
                onSelectAll = viewModel::selectAll,
                onClear = viewModel::clearSelection,
                onDownload = { viewModel.startDownload(context) }
            )
        }
    }
}

@Composable
private fun ScanResultsContent(
    result: com.swarupbaral.smarttgdownloader.data.TelegramRepository.ScanResult,
    selected: Set<Long>,
    downloading: Boolean,
    fileProgress: Map<Long, DownloadViewModel.FileProgress>,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit
) {
    val activeEntries = fileProgress.entries.filter { it.key in selected }
    val totalSelectedBytes = result.files
        .filter { it.messageId in selected }
        .sumOf { it.sizeBytes }
    val downloadedTotalBytes = activeEntries.sumOf { entry ->
        val progress = entry.value
        if (progress.isDone) {
            // Use the row's declared size when the final tick overshoots/undershoots.
            result.files.firstOrNull { it.messageId == entry.key }?.sizeBytes
                ?: progress.downloadedBytes
        } else {
            progress.downloadedBytes
        }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { paneTitle = "Scan results" }
    ) {
        Text(
            "Source: ${result.sourceTitle}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            "${result.files.size} matching files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSelectAll) { Text("Select all") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
        Spacer(Modifier.height(4.dp))

        if (downloading && totalSelectedBytes > 0) {
            val overall = (downloadedTotalBytes.toFloat() / totalSelectedBytes).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                }
            ) {
                LinearProgressIndicator(
                    progress = { overall },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Overall download progress"
                            progressBarRangeInfo = ProgressBarRangeInfo(overall, 0f..1f)
                        }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(downloadedTotalBytes)} of ${formatBytes(totalSelectedBytes)} · " +
                        "${activeEntries.size}/${selected.size} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(result.files, key = { it.messageId }) { file ->
                FileRow(
                    file = file,
                    isSelected = file.messageId in selected,
                    progress = fileProgress[file.messageId],
                    downloading = downloading,
                    onToggle = { onToggle(file.messageId) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDownload,
            enabled = selected.isNotEmpty() && !downloading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                if (downloading) "Downloading…" else "Download ${selected.size} selected"
            )
        }
    }
}

@Composable
private fun FileRow(
    file: com.swarupbaral.smarttgdownloader.data.TelegramRepository.ScannedFile,
    isSelected: Boolean,
    progress: DownloadViewModel.FileProgress?,
    downloading: Boolean,
    onToggle: () -> Unit
) {
    val finished = downloading && progress?.isDone == true
    val rowState = when {
        finished -> "Downloaded"
        isSelected -> "Selected"
        else -> "Not selected"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // One merged TalkBack node per file: name, size, selection state and
        // the checkbox role are announced together, with a full-height tap
        // target (the checkbox itself stays >48dp even though it's passive).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = isSelected,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() }
                )
                .semantics { stateDescription = rowState }
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clearAndSetSemantics { }
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${file.category} · ${formatBytes(file.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            progress?.takeIf { downloading && it.isDone }?.let {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        val activeProgress = progress?.takeIf { downloading && isSelected && !it.isDone }
        if (activeProgress != null) {
            LinearProgressIndicator(
                progress = { activeProgress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp, end = 4.dp, bottom = 2.dp)
                    .semantics {
                        contentDescription = "Downloading ${file.name}"
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(activeProgress.fraction, 0f..1f)
                    },
                strokeCap = StrokeCap.Round,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                "${formatBytes(activeProgress.downloadedBytes)} / ${formatBytes(activeProgress.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 52.dp)
            )
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
