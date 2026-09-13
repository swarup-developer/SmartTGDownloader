package com.swarupbaral.smarttgdownloader.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.SavedRange
import com.swarupbaral.smarttgdownloader.data.TelegramRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val savedRange by viewModel.lastRange.collectAsState()
    val failedDownloads by viewModel.failedDownloads.collectAsState()
    // Resolved here: the download lambda is not a composable scope.
    val context = LocalContext.current

    // The progress notification needs POST_NOTIFICATIONS on Android 13+. The
    // download itself runs either way — declining only hides the notification,
    // so this is a one-tap ask at the moment it becomes relevant, not a gate.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.startDownload(context) }

    val startDownload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startDownload(context)
        }
    }

    // Keep last-known per-file progress while the results sheet is open.
    val fileProgress = state.fileProgress
    // The link is what reveals the file-type step. Derived from the field
    // itself, so it survives a config change and needs no extra state.
    val linkEntered = state.link.isNotBlank()

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
        StepHeading(1, "Paste the Telegram link")
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

        // The file-type step appears once there is a link to analyze. The type
        // still has to be chosen before the scan starts — the scan collects one
        // type — but it is asked for once the user has committed to a link,
        // rather than as a question floating above an empty field.
        if (linkEntered) {
            Spacer(Modifier.height(24.dp))
            StepHeading(2, "Choose the file type to download")
            Spacer(Modifier.height(2.dp))
            // A live region, so the step that just appeared below the field is
            // announced instead of silently arriving somewhere off-screen.
            Text(
                "Pick the type to download, then analyze. The scan looks for this type only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(8.dp))
            FilterSection(
                selected = state.categoryFilter,
                onSelect = viewModel::setFilter
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::analyze,
                enabled = !state.analyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(analyzeLabel(state.analyzing, state.categoryFilter))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Files are saved to Download/${com.swarupbaral.smarttgdownloader.data.DownloadsStore.ROOT}. " +
                "No storage permission is needed, and downloads keep running if you leave the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        state.statusMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        state.errorMessage?.let {
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        // The explicit "nothing here" answer. Rendered inline rather than in
        // the results sheet, because an empty sheet is easy to mistake for a
        // failed scan.
        state.emptyResult?.let { empty ->
            Spacer(Modifier.height(20.dp))
            EmptyResultCard(empty)
        }

        // Failures are reported on the screen itself, not only inside the
        // results sheet: the sheet can be dismissed, and a file that failed to
        // download is still the user's problem after that.
        if (failedDownloads.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            FailedDownloadsCard(
                count = failedDownloads.size,
                onRetryAll = { viewModel.retryFailedDownloads(context) },
                onDismiss = { viewModel.dismissFailedDownloads(context) }
            )
        }

        if (state.analyzing) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
            state.scanProgress?.let {
                Spacer(Modifier.height(8.dp))
                // Deliberately NOT a live region: this ticks once per history
                // page, and announcing every page of a 500-page channel would
                // bury the user in speech. Focusable, so it can be read.
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = viewModel::cancelScan) { Text("Stop analyzing") }
        }
    }

    val result = state.scanResult
    val visibleFiles = state.visibleFiles
    if (result != null && visibleFiles.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissResults) {
            ScanResultsContent(
                sourceTitle = result.sourceTitle,
                filter = state.categoryFilter,
                foundCount = result.files.size,
                limitReached = result.limitReached,
                scanLimit = result.fileLimit,
                otherMediaCount = result.otherMediaCount,
                historyIncomplete = result.historyIncomplete,
                savedRange = savedRange,
                files = visibleFiles,
                selected = state.selected,
                selectedBytes = state.selectedBytes,
                unknownSizeCount = state.unknownSizeCount,
                stage = state.stage,
                downloading = state.downloading,
                fileProgress = fileProgress,
                failures = state.failures,
                onToggle = viewModel::toggleSelection,
                onChooseAll = viewModel::chooseAll,
                onChooseRange = viewModel::chooseRange,
                onBackToChoose = viewModel::backToChoose,
                onSelectRange = viewModel::selectRange,
                onSearchDeeper = viewModel::searchDeeper,
                onRescan = viewModel::analyze,
                onRetry = { messageId -> viewModel.retryFailedDownloads(context, messageId) },
                onDownload = startDownload
            )
        }
    }
}

/** "1 · Choose the file type" — numbered so the required order is spoken. */
@Composable
private fun StepHeading(step: Int, text: String) {
    Text(
        "$step · $text",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
}

private fun analyzeLabel(analyzing: Boolean, filter: String): String = when {
    analyzing -> "Analyzing…"
    filter == AppPreferences.CATEGORY_ALL -> "Analyze link"
    else -> "Analyze link for $filter files"
}

/**
 * The app's only filter control.
 *
 * Options come from [AppPreferences.DOWNLOAD_CATEGORIES] — the same list
 * Settings renders — and the selection is persisted there, so the two screens
 * can never drift apart or offer a value the other does not know about.
 *
 * The chips only: the step's heading and explanation are rendered by the caller,
 * so the section is not announced twice.
 */
@Composable
private fun FilterSection(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (category in AppPreferences.DOWNLOAD_CATEGORIES) {
            val isSelected = selected == category
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(category) },
                label = { Text(category) },
                modifier = Modifier.semantics {
                    // One choice out of a set, announced as such.
                    role = Role.RadioButton
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                }
            )
        }
    }
}

@Composable
private fun EmptyResultCard(empty: DownloadViewModel.EmptyResult) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics {
                    paneTitle = "Analysis result"
                    liveRegion = LiveRegionMode.Polite
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SearchOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    empty.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                empty.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}

/**
 * Failures with a way out of them.
 *
 * Nothing here retries on its own: a file that already failed three times is
 * not going to succeed while the user is not looking, so the choice is theirs.
 */
@Composable
private fun FailedDownloadsCard(
    count: Int,
    onRetryAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics {
                    paneTitle = "Download failures"
                    liveRegion = LiveRegionMode.Polite
                }
        ) {
            Text(
                if (count == 1) "1 download failed" else "$count downloads failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "They are not retried automatically. Retry to try again, or dismiss to forget them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetryAll) { Text("Retry failed downloads") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ScanResultsContent(
    sourceTitle: String,
    filter: String,
    foundCount: Int,
    limitReached: Boolean,
    scanLimit: Int,
    otherMediaCount: Int,
    historyIncomplete: Boolean,
    savedRange: SavedRange,
    files: List<TelegramRepository.ScannedFile>,
    selected: Set<Long>,
    selectedBytes: Long,
    unknownSizeCount: Int,
    stage: DownloadViewModel.SelectionStage,
    downloading: Boolean,
    fileProgress: Map<Long, DownloadViewModel.FileProgress>,
    failures: Map<Long, String>,
    onToggle: (Long) -> Unit,
    onChooseAll: () -> Unit,
    onChooseRange: () -> Unit,
    onBackToChoose: () -> Unit,
    onSelectRange: (Int, Int) -> Unit,
    onSearchDeeper: () -> Unit,
    onRescan: () -> Unit,
    onRetry: (Long) -> Unit,
    onDownload: () -> Unit
) {
    val activeEntries = fileProgress.entries.filter { it.key in selected }
    val downloadedTotalBytes = activeEntries.sumOf { entry ->
        val progress = entry.value
        if (progress.isDone) {
            // Use the row's declared size when the final tick overshoots/undershoots.
            files.firstOrNull { it.messageId == entry.key }?.sizeBytes
                ?: progress.downloadedBytes
        } else {
            progress.downloadedBytes
        }
    }

    // "200 Audio files" / "200 files" — the type is named whenever it is known.
    val countPhrase = buildString {
        append(foundCount)
        if (filter != AppPreferences.CATEGORY_ALL) append(" $filter")
        append(if (foundCount == 1) " file" else " files")
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { paneTitle = "Scan results" }
    ) {
        Text(
            "Source: $sourceTitle",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )

        // A single live region whose text changes at every step, so each step is
        // announced once and in order: the count first, then the chosen count
        // with the total size. Two live regions would talk over each other.
        val summary = when (stage) {
            DownloadViewModel.SelectionStage.CHOOSE ->
                "$countPhrase found. Do you want to download all $foundCount files, " +
                    "or would you like to select a range?"

            DownloadViewModel.SelectionStage.RANGE ->
                "$countPhrase found. Choose a range of files to download."

            DownloadViewModel.SelectionStage.CONFIRM ->
                "You have selected ${selected.size} of $foundCount files. " +
                    "Total download size: ${totalSizePhrase(selectedBytes, unknownSizeCount)}."
        }
        Spacer(Modifier.height(4.dp))
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        if (limitReached) {
            // Say plainly that the list is truncated: without this, a partial
            // scan is indistinguishable from a complete one.
            Text(
                "Scanning stopped at this channel's ${AppPreferences.scanLimitLabel(scanLimit)}" +
                    "-file limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            // The per-channel limit is raised right here, where the truncation
            // is reported, rather than by sending the user to a global setting
            // that would then apply to every other channel as well.
            AppPreferences.nextScanLimit(scanLimit)?.let { next ->
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onSearchDeeper,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (next <= 0) "Search the whole channel"
                        else "Search deeper (${AppPreferences.scanLimitLabel(next)} files)"
                    )
                }
            }
        }
        if (historyIncomplete) {
            // Say that the list may be short, and offer the retry right here:
            // on a large channel this is a "come back in a moment" situation,
            // not a property of the channel.
            Text(
                "Telegram had not finished loading this channel's older messages, " +
                    "so files may be missing from this list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan again")
            }
        }
        if (!historyIncomplete && otherMediaCount > 0 &&
            stage == DownloadViewModel.SelectionStage.CHOOSE
        ) {
            // The scan only looked for the chosen type, so say what else is
            // here — otherwise a user who picked the wrong chip has no way to
            // find out without scanning again.
            Text(
                "This channel also has about $otherMediaCount other media file(s). " +
                    "Change the file type to scan for those.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(12.dp))

        when (stage) {
            DownloadViewModel.SelectionStage.CHOOSE -> {
                Button(
                    onClick = onChooseAll,
                    enabled = foundCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download all $foundCount files")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onChooseRange,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select a range")
                }
            }

            DownloadViewModel.SelectionStage.RANGE -> {
                RangeSelector(
                    totalFiles = files.size,
                    saved = savedRange,
                    onApply = onSelectRange
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onBackToChoose) { Text("Back to all or range") }
            }

            DownloadViewModel.SelectionStage.CONFIRM -> {
                TextButton(onClick = onBackToChoose) { Text("Change selection") }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (downloading && selectedBytes > 0) {
            val overall = (downloadedTotalBytes.toFloat() / selectedBytes).coerceIn(0f, 1f)
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
                    "${formatBytes(downloadedTotalBytes)} of ${formatBytes(selectedBytes)} · " +
                        "${activeEntries.size}/${selected.size} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(files, key = { it.messageId }) { file ->
                FileRow(
                    file = file,
                    isSelected = file.messageId in selected,
                    progress = fileProgress[file.messageId],
                    downloading = downloading,
                    failure = failures[file.messageId],
                    onToggle = { onToggle(file.messageId) },
                    onRetry = { onRetry(file.messageId) }
                )
            }
        }

        // The confirmation the user asked for before spending data: how many
        // files, and how much they add up to. It only appears once a selection
        // exists, so the download action is never reachable without a size.
        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "You have selected ${selected.size} file(s). The total download size is " +
                    "${totalSizePhrase(selectedBytes, unknownSizeCount)}.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDownload,
                enabled = !downloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .semantics {
                        // The label says what the action is; this says how
                        // much, which is the number that decides the tap.
                        stateDescription = "${selected.size} file(s), " +
                            totalSizePhrase(selectedBytes, unknownSizeCount)
                    }
            ) {
                Text(if (downloading) "Downloading…" else "Start download")
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Picks a slice of the results by file number.
 *
 * Built for the case where a channel holds far more matches than anyone would
 * tick by hand: a 50,000-file channel can be narrowed to 20 files by typing the
 * first and last number, with no scrolling at all. The numbering matches the
 * positions in the list, and the fields reset when a new result set arrives so
 * they can never refer to a list that is no longer on screen.
 */
@Composable
private fun RangeSelector(
    totalFiles: Int,
    saved: SavedRange,
    onApply: (Int, Int) -> Unit
) {
    // Seed from the remembered range, clamped to what THIS result set holds: a
    // range carried over from a bigger channel must not come back invalid and
    // make the first tap an error. Re-seeded whenever the list size changes, so
    // a new analysis starts from the remembered values rather than the last
    // thing typed into a stale list. The clamp is [SavedRange.seededFor], which
    // is unit-tested — the picker only renders what it returns.
    val seed = saved.seededFor(totalFiles)
    var from by rememberSaveable(totalFiles) { mutableStateOf(seed.from.toString()) }
    var to by rememberSaveable(totalFiles) { mutableStateOf(seed.to.toString()) }

    Text(
        "Select a range",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(2.dp))
    Text(
        "Download just part of the list by file number — number 1 is the most recent " +
            "file. Useful when a channel has far more files than you need.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(8.dp))

    // One-tap presets for the common "just give me the newest few" case.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (preset in AppPreferences.RANGE_PRESETS) {
            // Disabled rather than clamped: a button labelled "Newest 100" that
            // silently selected 20 would be lying about what it does.
            OutlinedButton(
                onClick = {
                    from = "1"
                    to = preset.toString()
                    onApply(1, preset)
                },
                enabled = preset <= totalFiles
            ) {
                Text("Newest $preset")
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = from,
            onValueChange = { input -> from = input.filter(Char::isDigit).take(7) },
            label = { Text("From") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "First file number in the range" }
        )
        OutlinedTextField(
            value = to,
            onValueChange = { input -> to = input.filter(Char::isDigit).take(7) },
            label = { Text("To") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Last file number in the range" }
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onApply(from.toIntOrNull() ?: 0, to.toIntOrNull() ?: 0) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Select files $from to $to")
    }
}

@Composable
private fun FileRow(
    file: TelegramRepository.ScannedFile,
    isSelected: Boolean,
    progress: DownloadViewModel.FileProgress?,
    downloading: Boolean,
    failure: String?,
    onToggle: () -> Unit,
    onRetry: () -> Unit
) {
    val finished = downloading && progress?.isDone == true
    val rowState = when {
        failure != null -> "Failed"
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
        if (failure != null && !downloading) {
            // Per-file failure with its own retry: retrying one broken file
            // should not mean re-fetching the whole batch.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 52.dp)
            ) {
                Text(
                    "Failed: $failure",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetry) { Text("Retry") }
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

/**
 * Size wording that stays honest when Telegram did not report a size.
 *
 * A total built from unknown sizes is a lower bound, and saying "2.5 GB" for it
 * would be a guess presented as a fact.
 */
private fun totalSizePhrase(bytes: Long, unknownCount: Int): String =
    if (unknownCount > 0) {
        "at least ${formatBytes(bytes)} (size not reported for $unknownCount file(s))"
    } else {
        formatBytes(bytes)
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
