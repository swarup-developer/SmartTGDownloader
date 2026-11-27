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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.DownloadManager
import com.swarupbaral.smarttgdownloader.data.DownloadsStore
import com.swarupbaral.smarttgdownloader.data.SavedRange
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.data.rangeChoiceLabel
import com.swarupbaral.smarttgdownloader.data.searchDeeperActionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenMenu: () -> Unit,
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
    // Both start buttons need it, so the ask is shared: whichever was tapped is
    // what runs once the answer arrives.
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingStart?.invoke()
        pendingStart = null
    }

    val requireNotificationPermission: (() -> Unit) -> Unit = { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val startDownload = { requireNotificationPermission { viewModel.startDownload(context) } }
    val startZip = { requireNotificationPermission { viewModel.startZip(context) } }
    val cancelDownload = { viewModel.cancelDownload(context) }

    // Keep last-known per-file progress while the results sheet is open.
    val fileProgress = state.fileProgress
    // The link is what reveals the file-type step. Derived from the field
    // itself, so it survives a config change and needs no extra state.
    val linkEntered = state.link.isNotBlank()

    // The results sheet carries the same completion actions as this screen, so
    // Home's card stays hidden while the sheet is open: two live regions saying
    // the same thing would have a screen reader announce the batch twice.
    //
    // Read from the panel's own flag rather than from the rows on screen: the
    // panel can scan again from inside itself, and that rescan deliberately
    // clears the rows it is replacing.
    val resultsSheetOpen = state.panelOpen

    // Whether the "what do you want to download?" dialog is up.
    var showFilterDialog by remember { mutableStateOf(false) }

    // Where the files are, for the case where the device has no file manager to
    // show them. Local to this screen on purpose: it belongs to one tap, not to
    // the run.
    var folderMessage by remember { mutableStateOf<String?>(null) }
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
            .verticalScroll(rememberScrollState())
            // The same content margin History and Settings use. These screens had
            // 24dp and 16dp for the same kind of content, which reads as one of
            // them being wrong rather than as a deliberate difference.
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The menu is the way to everything in the app now, so it leads the
            // screen where a settings shortcut used to sit on the far right —
            // the same corner a back button occupies everywhere else.
            IconButton(onClick = onOpenMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "Open menu")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                // The app's full name, as the intro screen, the menu and the
                // about line all give it. This screen used to shorten it.
                "Smart TG Downloader",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
        }

        val inProgressOrDone = state.analyzing ||
            resultsSheetOpen ||
            state.emptyResult != null ||
            state.downloading ||
            state.runState.isTerminal

        if (!inProgressOrDone) {
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

            // Step 2 appears only once a link is typed, and disappears together
            // with Step 1 once analysis/results/download take over.
            if (linkEntered) {
                Spacer(Modifier.height(24.dp))
                StepHeading(2, "Analyze the link")
                Spacer(Modifier.height(2.dp))
                Text(
                    if (state.askBeforeAnalyzing) {
                        "Analyze asks what you want to download — Audio, Video, Images, " +
                            "Docs or All."
                    } else {
                        "Analyze looks for ${state.categoryFilter} files automatically. " +
                            "You can change this in Settings or below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (state.askBeforeAnalyzing) showFilterDialog = true
                        else viewModel.analyzeWithoutAsking()
                    },
                    enabled = !state.analyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = if (state.askBeforeAnalyzing) {
                                "Analyze link"
                            } else {
                                "Analyze the link for ${state.categoryFilter} files, " +
                                    "without asking which type"
                            }
                        }
                ) {
                    Text("Analyze link")
                }
                if (!state.askBeforeAnalyzing) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { showFilterDialog = true },
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Change type, currently ${state.categoryFilter}, and choose " +
                                    "whether the question is asked again"
                        }
                    ) {
                        Text("Change type")
                    }
                }
            } else {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Files are saved to Download/${DownloadsStore.ROOT}/<channel>/ — one folder " +
                        "per channel. No storage permission is needed, and downloads keep running " +
                        "if you leave the app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    state.scanResult?.sourceTitle?.takeIf { it.isNotBlank() } ?: state.link,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!state.analyzing && !state.downloading) {
                    TextButton(onClick = viewModel::backToLinkEdit) {
                        Text("Change link")
                    }
                }
            }
        }

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
        if (state.downloading && !resultsSheetOpen) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.progressText?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = cancelDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel download")
            }
        }

        // The batch is over: where the files went, and a way to go and look. It
        // is NOT opened automatically — being thrown out of the app the moment a
        // download completes is disorienting, and a screen reader user would
        // have their focus moved somewhere they never asked to go.
        if (state.runState.isTerminal && !resultsSheetOpen) {
            Spacer(Modifier.height(20.dp))
            DownloadFinishedCard(
                message = state.runState.message ?: "Download finished.",
                folder = state.runState.lastSavedFolder,
                folderMessage = folderMessage,
                onOpenFolder = openFolder,
                onDismiss = viewModel::acknowledgeRun
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::startNewLink,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download another link")
            }
        }

        // The explicit "nothing here" answer. Rendered inline rather than in
        // the results sheet, because an empty sheet is easy to mistake for a
        // failed scan.
        //
        // Hidden while the sheet is open, which matters twice over: this card is a
        // live region, so the sheet's own copy would have the answer announced
        // twice, and any action under it would exist twice as well — once behind a
        // modal sheet the user cannot reach.
        if (!resultsSheetOpen) {
            state.emptyResult?.let { empty ->
                Spacer(Modifier.height(20.dp))
                EmptyResultCard(empty)
                // The advice inside the card above says to search deeper when the
                // walk was cut short, so the control it names is here under it.
                empty.searchDeeperLabel?.let { label ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = viewModel::searchDeeper,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
                if (empty.anotherTypeMayHelp) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showFilterDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Change type")
                    }
                }
            }
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

        // Only when the panel is not up: the panel can start a scan of its own,
        // and it reports that one itself. Two surfaces showing the same scan
        // would leave a Stop button here that is covered by the sheet and cannot
        // be reached.
        if (state.analyzing && !resultsSheetOpen) {
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
    if (resultsSheetOpen) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissResults) {
            // No file-type control in here: the type is settled in the question
            // Analyze asks, and offering it a second time from the panel made the
            // same decision reachable from two places that could disagree about
            // what the scan had looked for.
            when {
                state.analyzing -> ResultsPanelScanning(
                    filter = state.categoryFilter,
                    progress = state.scanProgress,
                    onCancel = viewModel::cancelScan
                )

                result == null || visibleFiles.isEmpty() -> ResultsPanelNothing(
                    empty = state.emptyResult,
                    statusMessage = state.statusMessage,
                    // Reopens the Analyze question rather than showing a list of
                    // types in here: the type is chosen in one place, and this is
                    // the panel handing the user back to it.
                    onChangeType = { showFilterDialog = true },
                    onSearchDeeper = viewModel::searchDeeper,
                    onRescan = viewModel::analyze,
                    onClose = viewModel::dismissResults
                )

                else -> ScanResultsContent(
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
                    runState = state.runState,
                    folderMessage = folderMessage,
                    onOpenFolder = openFolder,
                    onAcknowledgeRun = viewModel::acknowledgeRun,
                    onToggle = viewModel::toggleSelection,
                    onChooseAll = viewModel::chooseAll,
                    onChooseRange = viewModel::chooseRange,
                    onBackToChoose = viewModel::backToChoose,
                    onSelectRange = viewModel::selectRange,
                    onSearchDeeper = viewModel::searchDeeper,
                    onRescan = viewModel::analyze,
                    onRetry = { messageId -> viewModel.retryFailedDownloads(context, messageId) },
                    keepZipFiles = state.keepZipFiles,
                    onKeepZipFilesChange = viewModel::setKeepZipFiles,
                    onDownload = startDownload,
                    onDownloadZip = startZip,
                    onCancelDownload = cancelDownload,
                    onStartNewLink = viewModel::startNewLink
                )
            }
        }
    }

    // Asked for on the press of Analyze, and only then: the type is a decision
    // about this scan, not a setting to be stared at while typing a link.
    if (showFilterDialog) {
        FilterChoiceDialog(
            selected = state.categoryFilter,
            dontAskAgain = !state.askBeforeAnalyzing,
            onChoose = { category, dontAskAgain ->
                showFilterDialog = false
                viewModel.analyzeWithFilter(category, dontAskAgain)
            },
            onDismiss = { showFilterDialog = false }
        )
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

/**
 * Asks which type of file to look for, at the moment Analyze is pressed.
 *
 * The options are [AppPreferences.DOWNLOAD_CATEGORIES] — the same list Settings
 * offers — so no screen can invent a type the others do not know about.
 *
 * Confirm-then-scan rather than tap-to-choose: scanning a large channel is a real
 * wait, and one stray tap should not start one.
 *
 * **Don't ask again** turns the question into a remembered answer: the type is
 * kept and the next Analyze scans straight away. Its box starts from the current
 * preference rather than always unchecked, so the dialog states the truth about
 * what will happen — and unticking it is how the question is asked again.
 */
@Composable
private fun FilterChoiceDialog(
    selected: String,
    dontAskAgain: Boolean,
    onChoose: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Local, so a change of mind inside the dialog scans nothing until Analyze is
    // pressed. Keyed on the current choice, so reopening shows what is set now.
    var choice by rememberSaveable(selected) { mutableStateOf(selected) }
    var skipQuestion by rememberSaveable(dontAskAgain) { mutableStateOf(dontAskAgain) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What do you want to download?") },
        text = {
            Column {
                Text(
                    "The scan looks for the type you pick. " +
                        "\"${AppPreferences.CATEGORY_ALL}\" takes every file in the " +
                        "link, whatever its type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(4.dp))
                // Only the options are a selectable group. The "don't ask again"
                // box below is a switch, and folding it into the group would have
                // a screen reader announce it as one of the mutually exclusive
                // choices when it is not one.
                Column(modifier = Modifier.selectableGroup()) {
                    for (category in AppPreferences.DOWNLOAD_CATEGORIES) {
                        val isSelected = choice == category
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { choice = category }
                                )
                                // A full row per option, so the whole line is the
                                // target rather than the 20dp radio circle.
                                .padding(vertical = 10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                modifier = Modifier.clearAndSetSemantics { }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(category, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = skipQuestion,
                            role = Role.Checkbox,
                            onValueChange = { skipQuestion = it }
                        )
                        .semantics {
                            contentDescription = "Don't ask again"
                            stateDescription =
                                if (skipQuestion) "Checked" else "Not checked"
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Checkbox(
                        checked = skipQuestion,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Don't ask again", style = MaterialTheme.typography.bodyLarge)
                        // Names the type, so the box is not a promise about an
                        // unnamed decision — and says where it can be undone.
                        Text(
                            "Uses $choice for every scan. You can change this in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onChoose(choice, skipQuestion) },
                modifier = Modifier.semantics {
                    // Names the action it is about to take, including the type
                    // and whether it will be asked again, so the press is never
                    // a guess.
                    contentDescription = buildString {
                        if (choice == AppPreferences.CATEGORY_ALL) {
                            append("Analyze the link for every file")
                        } else {
                            append("Analyze the link for $choice files")
                        }
                        append(
                            if (skipQuestion) ", and stop asking which type"
                            else ""
                        )
                    }
                }
            ) {
                Text("Analyze")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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

/**
 * The panel while a scan it started is running.
 *
 * Reachable when a scan was cut short because Telegram had not finished loading
 * the channel's older messages: the *Scan again* action sits in the panel, so the
 * panel has to report the scan it just began. It is not a type change — the type
 * lives in the Analyze question, and the panel never scans for a different one.
 *
 * The type being scanned is stated in a live region, because the rows the user
 * was looking at have just been cleared and something has to explain the gap.
 * The per-page progress below it is deliberately not a live region — on a large
 * channel it ticks once per history page, and announcing every page would bury
 * the sentence that matters.
 */
@Composable
private fun ResultsPanelScanning(
    filter: String,
    progress: String?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { paneTitle = "Scanning" }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (filter == AppPreferences.CATEGORY_ALL) {
                    "Scanning again for every file type."
                } else {
                    "Scanning again for $filter files."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        progress?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text("Stop scanning") }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The panel when there is nothing to list: a scan that found no files of the
 * chosen type, or one that was stopped.
 *
 * It stays open rather than collapsing, so the answer is in front of the user
 * instead of back on the Home screen behind a dismissed sheet: *Search deeper*
 * when the walk was cut short by the channel's limit, *Change type* when it passed
 * over other media, *Scan again* for a walk that was unfinished or stopped, and
 * *Close results* to go back. Which of the first two is offered comes from the
 * empty result itself — see [DownloadViewModel.EmptyResult] — so the panel never
 * proposes a fix that cannot help, and never names one the card has not raised.
 */
@Composable
private fun ResultsPanelNothing(
    empty: DownloadViewModel.EmptyResult?,
    statusMessage: String?,
    onChangeType: () -> Unit,
    onSearchDeeper: () -> Unit,
    onRescan: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        when {
            empty != null -> EmptyResultCard(empty)
            statusMessage != null -> Text(
                statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            else -> Text(
                "No files are listed for this type.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(12.dp))
        // Offered only for the one empty answer whose fix is a different type —
        // the walk found nothing of the type asked for but did pass over other
        // media. It is a single action, not a second list of types: it reopens the
        // Analyze question, which is where the type is chosen, so the decision
        // still exists in exactly one place and the panel holds no copy of it.
        // Each action is offered only when the card above asks for it: a deeper
        // search when the walk was cut short by the channel's limit, and a type
        // change when the walk passed over other media. Both are outlined rather
        // than filled, because when both apply neither is more *the* answer than
        // the other — the card says what each would find, and this screen has no
        // way to rank them.
        empty?.searchDeeperLabel?.let { label ->
            OutlinedButton(
                onClick = onSearchDeeper,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(label)
            }
            Spacer(Modifier.height(4.dp))
        }
        if (empty?.anotherTypeMayHelp == true) {
            OutlinedButton(
                onClick = onChangeType,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "Change file type: reopens the question that chooses it"
                    }
            ) {
                Text("Change type")
            }
            Spacer(Modifier.height(4.dp))
        }
        // Retrying the same type on purpose: an unfinished walk, or one that was
        // stopped, is fixed by asking again — not by changing the type.
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan again")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close results")
        }
        Spacer(Modifier.height(24.dp))
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
    runState: DownloadManager.RunState,
    folderMessage: String?,
    onOpenFolder: (String?) -> Unit,
    onAcknowledgeRun: () -> Unit,
    onToggle: (Long) -> Unit,
    onChooseAll: () -> Unit,
    onChooseRange: () -> Unit,
    onBackToChoose: () -> Unit,
    onSelectRange: (Int, Int) -> Unit,
    onSearchDeeper: () -> Unit,
    onRescan: () -> Unit,
    onRetry: (Long) -> Unit,
    keepZipFiles: Boolean,
    onKeepZipFilesChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDownloadZip: () -> Unit,
    onCancelDownload: () -> Unit,
    onStartNewLink: () -> Unit
) {
    var showFileList by rememberSaveable(sourceTitle, foundCount) { mutableStateOf(false) }
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
        if (runState.isTerminal) {
            StepHeading(4, "Download finished")
            Spacer(Modifier.height(6.dp))
            Text(
                "Source: $sourceTitle",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))
            DownloadFinishedCard(
                message = runState.message ?: "Download finished.",
                folder = runState.lastSavedFolder,
                folderMessage = folderMessage,
                onOpenFolder = onOpenFolder,
                onDismiss = onAcknowledgeRun
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStartNewLink,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download another link")
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        if (downloading) {
            StepHeading(4, if (runState.packing) "Creating ZIP" else "Downloading")
            Spacer(Modifier.height(6.dp))
            Text(
                "Source: $sourceTitle",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                }
            ) {
                if (selectedBytes > 0) {
                    val overall = (downloadedTotalBytes.toFloat() / selectedBytes).coerceIn(0f, 1f)
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
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${activeEntries.size}/${selected.size} files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancelDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel download")
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        StepHeading(3, "Choose what to download")
        Spacer(Modifier.height(8.dp))
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

        if (stage == DownloadViewModel.SelectionStage.CHOOSE) {
            if (limitReached) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scanning stopped at this channel's ${AppPreferences.scanLimitLabel(scanLimit)}" +
                        "-file limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                searchDeeperActionLabel(scanLimit)?.let { label ->
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onSearchDeeper,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
            }
            if (historyIncomplete) {
                Spacer(Modifier.height(6.dp))
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
            if (!historyIncomplete && otherMediaCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This channel also has about $otherMediaCount other media file(s). " +
                        "Close this panel and Analyze the link again to look for those.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when (stage) {
            DownloadViewModel.SelectionStage.CHOOSE -> {
                Button(
                    onClick = {
                        showFileList = false
                        onChooseAll()
                    },
                    enabled = foundCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download all $foundCount files")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Or download part of the list by choosing a range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                RangeChoiceControl(
                    totalFiles = files.size,
                    saved = savedRange,
                    onApply = { from, to ->
                        showFileList = false
                        onSelectRange(from, to)
                    },
                    onCustom = {
                        showFileList = false
                        onChooseRange()
                    }
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showFileList = !showFileList },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showFileList) "Hide file list"
                        else "Browse & pick individual files ($foundCount)"
                    )
                }
                if (showFileList) {
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
                }
            }

            DownloadViewModel.SelectionStage.RANGE -> {
                RangeSelector(
                    totalFiles = files.size,
                    saved = savedRange,
                    onApply = { from, to ->
                        showFileList = false
                        onSelectRange(from, to)
                    }
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onBackToChoose) { Text("Back to all or range") }
            }

            DownloadViewModel.SelectionStage.CONFIRM -> {
                if (selected.isNotEmpty()) {
                    Text(
                        "You have selected ${selected.size} file(s). The total download size is " +
                            "${totalSizePhrase(selectedBytes, unknownSizeCount)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    stateDescription = "${selected.size} file(s), " +
                                        totalSizePhrase(selectedBytes, unknownSizeCount)
                                }
                        ) {
                            Text("Start download")
                        }
                        OutlinedButton(
                            onClick = onDownloadZip,
                            modifier = Modifier.semantics {
                                contentDescription = "Download ZIP. Saves the selected " +
                                    "${selected.size} file(s), " +
                                    "${totalSizePhrase(selectedBytes, unknownSizeCount)}, " +
                                    "as one zip archive."
                            }
                        ) {
                            Text("Download ZIP")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (keepZipFiles) {
                            "Download ZIP saves the same ${selected.size} file(s) as one .zip " +
                                "archive in the channel's folder, and keeps the separate files too."
                        } else {
                            "Download ZIP saves the same ${selected.size} file(s) as one .zip " +
                                "archive in the channel's folder, instead of as separate files."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = keepZipFiles,
                                role = Role.Checkbox,
                                onValueChange = onKeepZipFilesChange
                            )
                            .semantics {
                                contentDescription = "Also keep the separate files"
                                stateDescription = if (keepZipFiles) "Kept" else "Not kept"
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = keepZipFiles,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics { }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Also keep the separate files",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                showFileList = false
                                onBackToChoose()
                            }
                        ) {
                            Text("Change selection")
                        }
                        TextButton(onClick = { showFileList = !showFileList }) {
                            Text(
                                if (showFileList) "Hide selected files"
                                else "Review files (${selected.size})"
                            )
                        }
                    }
                    if (showFileList) {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(files.filter { it.messageId in selected }, key = { it.messageId }) { file ->
                                FileRow(
                                    file = file,
                                    isSelected = true,
                                    progress = fileProgress[file.messageId],
                                    downloading = downloading,
                                    failure = failures[file.messageId],
                                    onToggle = { onToggle(file.messageId) },
                                    onRetry = { onRetry(file.messageId) }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
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
 *
 * Only the typed range lives here now. The one-tap presets moved into
 * [RangeChoiceControl], where choosing "Newest 20" is one entry in a list of
 * ranges rather than a button that had to be told apart from the fields below
 * it.
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
        "Custom range",
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

/**
 * The range, as a single combobox.
 *
 * The range used to be a row of preset buttons, two number fields and a third
 * button to apply what was typed — four controls for one decision, "how much of
 * this list do I want?". The presets are now the combobox's entries, and the
 * fields appear only for "Custom range…", which is the one entry that needs them.
 *
 * Presets that do not fit the list are disabled rather than clamped: an entry
 * reading "Newest 100 files" that silently selected 20 would be lying about what
 * it does, which is the rule the button row already followed.
 *
 * Accessibility: the anchor replaces its own read-only field semantics with one
 * description that carries the range currently in force, plus a label for what
 * opening it does. Without that, the field would announce as something the user
 * could type into, and a screen reader user would be told the value but not that
 * it is a list of choices. The pointer handling that opens the menu on a tap is
 * a modifier rather than a semantics action, so replacing the semantics leaves
 * touch and TalkBack activation both working. The entry matching the current
 * range is marked selected, so opening the list says where the user already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeChoiceControl(
    totalFiles: Int,
    saved: SavedRange,
    onApply: (Int, Int) -> Unit,
    onCustom: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = rangeChoiceLabel(saved, totalFiles)
    // The preset's own wording, so the entry and the value it becomes read the
    // same: see [rangeChoiceLabel].
    val presetLabel: (Int) -> String = { preset -> "Newest $preset files" }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Range") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                // The typed overload: a read-only combobox is exactly what
                // PrimaryNotEditable describes, and the no-argument overload is
                // deprecated — which this project's warning-free build treats as
                // a failure.
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = "Range of files to download"
                    stateDescription = current
                    role = Role.Button
                    onClick(label = "Open the range options") {
                        expanded = true
                        true
                    }
                }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            for (preset in AppPreferences.RANGE_PRESETS) {
                val fits = preset <= totalFiles
                DropdownMenuItem(
                    text = { Text(presetLabel(preset)) },
                    onClick = {
                        expanded = false
                        onApply(1, preset)
                    },
                    enabled = fits,
                    modifier = Modifier.semantics {
                        selected = fits && current == presetLabel(preset)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Custom range…") },
                onClick = {
                    expanded = false
                    onCustom()
                }
            )
        }
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

/**
 * "The batch is done, and here is where it went."
 *
 * The folder buttons are the point: they answer "where are my files?" without
 * the user having to know that MediaStore writes into Downloads at all.
 */
@Composable
private fun DownloadFinishedCard(
    message: String,
    folder: String?,
    folderMessage: String?,
    onOpenFolder: (String?) -> Unit,
    onDismiss: () -> Unit
) {
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
                    paneTitle = "Download finished"
                    // One live region for the whole card, so the summary and the
                    // actions arrive together as one announcement.
                    liveRegion = LiveRegionMode.Polite
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Spacer(Modifier.height(8.dp))
            OpenFolderActions(folder = folder, onOpenFolder = onOpenFolder)
            folderMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * "Show me the files", as two buttons at most.
 *
 * The channel folder is only offered when the batch actually saved something
 * into it ([folder] is null otherwise) — MediaStore creates the directory with
 * the first file, so a button offered earlier would open a folder that does not
 * exist.
 */
@Composable
private fun OpenFolderActions(folder: String?, onOpenFolder: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { onOpenFolder(null) },
            modifier = Modifier.semantics {
                // The full location is spoken, because "the Downloads folder"
                // on its own does not say which folder the files are in.
                contentDescription =
                    "Open Downloads folder, ${DownloadsStore.displayPath()}"
            }
        ) {
            Text("Open Downloads folder")
        }
        if (folder != null) {
            val channel = DownloadsStore.channelName(folder)
            OutlinedButton(
                onClick = { onOpenFolder(folder) },
                modifier = Modifier.semantics {
                    contentDescription =
                        "Open $channel folder, ${DownloadsStore.displayPath(folder)}"
                }
            ) {
                Text("Open $channel folder")
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
