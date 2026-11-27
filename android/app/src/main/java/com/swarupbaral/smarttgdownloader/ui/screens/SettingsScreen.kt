package com.swarupbaral.smarttgdownloader.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.ChannelSettings
import com.swarupbaral.smarttgdownloader.data.DownloadNetwork
import com.swarupbaral.smarttgdownloader.data.DownloadsStore
import com.swarupbaral.smarttgdownloader.data.ThemeMode
import com.swarupbaral.smarttgdownloader.data.scanLimitChoiceLabel
import com.swarupbaral.smarttgdownloader.data.scanLimitChoicesFor
import com.swarupbaral.smarttgdownloader.update.UpdateManager

/**
 * Settings, split into the groups it always had.
 *
 * The screen shows one group at a time, chosen from the section row at the top
 * or asked for by the app menu. What used to be a single scrolling column is
 * now six short screens: a group can be reached in one tap instead of by
 * scrolling past the groups above it, and each one can say what it is about
 * without the others crowding it.
 *
 * The group is [section], held by the caller rather than in here, so the menu
 * and this screen can never disagree about which one is showing: the item the
 * menu marks as current is the group this screen is actually on, including when
 * the group was changed from the section row below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    section: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    var pendingForgetAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // The menu is here as well as on Home and History: it holds the
                // way to everything else in the app, and a screen whose only exit
                // is the way you came in is a dead end for a switch user.
                actions = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SectionChooser(selected = section, onSelect = onSelectSection)
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 24.dp)
                    // One pane per group, so moving between sections is announced
                    // as moving somewhere, not just as different text.
                    .semantics { paneTitle = "${section.label} settings" }
            ) {
                when (section) {
                    SettingsSection.ACCOUNT -> AccountSection(viewModel)

                    SettingsSection.APPEARANCE -> AppearanceSection(viewModel)

                    SettingsSection.ANALYSIS -> AnalysisSection(viewModel)

                    SettingsSection.DOWNLOADS -> DownloadsSection(viewModel)

                    SettingsSection.CHANNELS ->
                        ChannelsSection(viewModel) { pendingForgetAll = true }

                    SettingsSection.UPDATES -> UpdatesSection(viewModel)
                }
            }
        }
    }

    if (pendingForgetAll) {
        AlertDialog(
            onDismissRequest = { pendingForgetAll = false },
            title = { Text("Forget all channels?") },
            text = {
                Text(
                    "Removes the remembered filter, range and scan limit for every channel. " +
                        "Your downloads and history are not affected."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.forgetAllChannels()
                        pendingForgetAll = false
                    },
                    // Coloured like the other destructive confirmations in the
                    // app (clearing history), because it is the same kind of
                    // action: the thing it removes cannot be got back.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { pendingForgetAll = false }) { Text("Cancel") }
            }
        )
    }

}

/**
 * The row of groups, always visible above the section it is showing.
 *
 * Chips rather than a menu so that every group is one tap away and none of them
 * hides the others: this is the screen's own navigation, and a section you
 * cannot see is a section you have to remember exists.
 *
 * Role is [Role.Tab]: the options switch the content below them rather than
 * being a value that is saved, and a screen reader then announces them as the
 * screen's tabs rather than as one of a set of radio choices.
 */
@Composable
private fun SectionChooser(selected: SettingsSection, onSelect: (SettingsSection) -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "Sections",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (option in SettingsSection.entries) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option.label) },
                    modifier = Modifier.semantics { role = Role.Tab }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The heading every group opens with.
 *
 * A polite live region, because choosing a section changes everything below this
 * line: without it the new group would arrive silently and only the tabs would
 * have been announced.
 */
@Composable
private fun SectionHeading(title: String, detail: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        }
    )
    Spacer(Modifier.height(4.dp))
    Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(16.dp))
}

/**
 * Who is signed in — and nothing else.
 *
 * This group used to end with a *Log out* button, which made ending the session a
 * thing you could only reach by scrolling a Settings group. Signing out is not a
 * setting: it is the last entry in the menu, reachable from every screen, and
 * this group says so rather than being a second place the same action lives.
 */
@Composable
private fun AccountSection(viewModel: SettingsViewModel) {
    val account by viewModel.accountLabel.collectAsState()

    SectionHeading(
        "Account",
        "The Telegram account this app is signed in as."
    )
    Text("Signed in as $account", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Signing out is the last entry in the menu. It clears the stored session; " +
            "your downloads and history stay on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

@Composable
private fun AppearanceSection(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()

    SectionHeading(
        "Appearance",
        "How the app is coloured. Follows the system by default."
    )
    Text("Theme", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (mode in ThemeMode.entries) {
            val isSelected = themeMode == mode
            FilterChip(
                selected = isSelected,
                onClick = { viewModel.setThemeMode(mode) },
                label = {
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                    )
                },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                }
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Use wallpaper colors", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (viewModel.dynamicColorSupported) {
                    "Match Material You palette (Android 12+)"
                } else {
                    "Requires Android 12 or newer"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
            enabled = viewModel.dynamicColorSupported,
            modifier = Modifier.semantics { contentDescription = "Dynamic color" }
        )
    }
}

/**
 * The analyze question, default file type, and the scan limit: the decisions
 * that belong to a scan rather than to a download.
 */
@Composable
private fun AnalysisSection(viewModel: SettingsViewModel) {
    val askBeforeAnalyzing by viewModel.askBeforeAnalyzing.collectAsState()
    val defaultCategory by viewModel.defaultCategory.collectAsState()
    val scanLimit by viewModel.scanLimit.collectAsState()

    SectionHeading(
        "Analysis",
        "What a scan looks for, how much of a channel it walks, and whether it " +
            "asks before it starts."
    )

    Text("Default file type", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        if (askBeforeAnalyzing) {
            "Pre-selected when you tap Analyze link."
        } else {
            "Used automatically for every scan while the question below is off."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (category in AppPreferences.DOWNLOAD_CATEGORIES) {
            val isSelected = defaultCategory == category
            FilterChip(
                selected = isSelected,
                onClick = { viewModel.setDefaultCategory(category) },
                label = { Text(category) },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                }
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ask which type before analyzing", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (askBeforeAnalyzing) {
                    "Analyze asks which type to look for before it scans."
                } else {
                    "Off: Analyze scans for $defaultCategory without asking. " +
                        "Change type on Home asks the question again."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = askBeforeAnalyzing,
            onCheckedChange = viewModel::setAskBeforeAnalyzing,
            modifier = Modifier.semantics {
                contentDescription = "Ask which type before analyzing"
                stateDescription = if (askBeforeAnalyzing) "On" else "Off"
            }
        )
    }

    Spacer(Modifier.height(24.dp))
    Text("Default scan limit", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "How many files one analysis collects before it stops, counting from the most " +
            "recent. Used for channels with nothing saved; each channel keeps its own " +
            "limit, which can be raised from its own results panel or set for it in " +
            "Channels.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (option in AppPreferences.SCAN_LIMIT_OPTIONS) {
            val isSelected = scanLimit == option
            FilterChip(
                selected = isSelected,
                onClick = { viewModel.setScanLimit(option) },
                label = { Text(scanLimitChoiceLabel(option)) },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                }
            )
        }
    }
}

@Composable
private fun DownloadsSection(viewModel: SettingsViewModel) {
    val downloadNetwork by viewModel.downloadNetwork.collectAsState()

    SectionHeading(
        "Downloads",
        "Which connections may be used, and where the files are saved."
    )
    Text("Download connection", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Which connections downloads may use. Checked before a download starts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (option in DownloadNetwork.entries) {
            val isSelected = downloadNetwork == option
            FilterChip(
                selected = isSelected,
                onClick = { viewModel.setDownloadNetwork(option) },
                label = { Text(option.label) },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                }
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        downloadNetwork.detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Spacer(Modifier.height(24.dp))
    Text("Where downloads go", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Files are saved straight into your Downloads folder as " +
            "Download/${DownloadsStore.ROOT}/<channel>/, so they show up " +
            "in the Files app and in any gallery or player. A folder is created " +
            "for each channel you download from. Saving there needs no " +
            "storage permission.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

@Composable
private fun ChannelsSection(viewModel: SettingsViewModel, onForgetAll: () -> Unit) {
    val channels by viewModel.channels.collectAsState()
    // Which channel's limit is being edited, as its chat id. 0 means none: the
    // store drops any entry whose id is 0, so it cannot collide with a real one.
    var editingChatId by rememberSaveable { mutableStateOf(0L) }

    SectionHeading(
        "Channels",
        if (channels.isEmpty()) {
            "Each channel you analyze remembers its own filter, range and scan " +
                "limit. None remembered yet."
        } else {
            "Each channel remembers its own filter, range and scan limit."
        }
    )
    for (channel in channels) {
        ChannelRow(
            channel = channel,
            onEditScanLimit = { editingChatId = channel.chatId },
            onForget = { viewModel.forgetChannel(channel.chatId) }
        )
    }
    if (channels.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onForgetAll,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Forget all channels") }
    }

    // Resolved from the live list rather than held as a copy, so forgetting a
    // channel while its dialog is open closes the dialog with it instead of
    // leaving an editor for something that is no longer remembered.
    val editing = channels.firstOrNull { it.chatId == editingChatId }
    if (editing != null) {
        ScanLimitDialog(
            channel = editing,
            onDismiss = { editingChatId = 0L },
            onConfirm = { limit ->
                viewModel.setChannelScanLimit(editing.chatId, limit)
                editingChatId = 0L
            }
        )
    }
}

/**
 * Picks one channel's scan limit — the depth its next analysis will walk.
 *
 * A dialog rather than chips opened inside the row: the ladder repeated once per
 * channel would fill a scrolling list with six near-identical chip rows, none of
 * which names the channel it belongs to when read aloud one button at a time. Here
 * the channel is in the title, which is also what a screen reader announces when
 * the dialog opens, so the choices cannot belong to the wrong channel.
 *
 * Every step is offered, not only the deeper ones. Deepening is the usual reason to
 * come here, but a control that could only go up would leave a limit that is too
 * large — or an "All" picked by mistake — with no way back except analyzing the
 * channel again.
 */
@Composable
private fun ScanLimitDialog(
    channel: ChannelSettings,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val choices = scanLimitChoicesFor(channel.scanLimit)
    // The same normalisation [scanLimitChoicesFor] applies, so the value that
    // starts selected is always one of the entries on screen — a radio group with
    // nothing chosen would read as a control the user cannot answer.
    val inForce = if (channel.scanLimit <= 0) 0 else channel.scanLimit
    // Keyed on the channel and its stored limit so the pending choice cannot leak
    // from one channel's dialog into the next one's.
    var chosen by remember(channel.chatId, channel.scanLimit) { mutableStateOf(inForce) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan limit for ${channel.title}") },
        text = {
            Column {
                Text(
                    "How many files analyzing this channel collects before it stops, " +
                        "counting from the most recent. The channel keeps this limit " +
                        "when it is analyzed again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                if (inForce !in AppPreferences.SCAN_LIMIT_OPTIONS) {
                    // Rare, but an unexplained extra number beside the steps would
                    // read as a mistake rather than as the value in force.
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Its limit of ${AppPreferences.scanLimitLabel(inForce)} " +
                            "files is not one of the usual steps, so it is listed with them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Only the choices form a selectable group, matching the analyze
                // dialog: a screen reader then reads them as one-of-N rather than
                // as unrelated buttons.
                Column(modifier = Modifier.selectableGroup()) {
                    for (option in choices) {
                        val isSelected = chosen == option
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { chosen = option }
                                )
                                // The whole line is the target, not the radio circle.
                                .padding(vertical = 10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                modifier = Modifier.clearAndSetSemantics { }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                scanLimitChoiceLabel(option),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(chosen) },
                // Names the channel, as the row's own buttons do: several of these
                // dialogs exist in one session, and "Set limit" alone says neither
                // which channel nor which value is about to be written.
                modifier = Modifier.semantics {
                    contentDescription =
                        "Set ${scanLimitChoiceLabel(chosen)} as the scan limit for " +
                            channel.title
                }
            ) { Text("Set limit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun UpdatesSection(viewModel: SettingsViewModel) {
    val autoCheckEnabled by viewModel.autoCheckEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val checking = updateState is UpdateManager.UpdateState.Checking

    SectionHeading(
        "Updates",
        "Check GitHub for a newer version, and decide whether the app does that " +
            "on its own."
    )
    Button(
        onClick = viewModel::checkForUpdates,
        enabled = !checking,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (checking) "Checking…" else "Check for updates")
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "The result appears as a prompt, wherever you are in the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Automatic update checks", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Check GitHub for new versions on launch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = autoCheckEnabled,
            onCheckedChange = viewModel::setAutoCheckEnabled,
            modifier = Modifier.semantics { contentDescription = "Automatic update checks" }
        )
    }
    Spacer(Modifier.height(24.dp))
    Text(
        "Smart TG Downloader v${viewModel.currentVersionName} · native Android rebuild",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

/** One remembered channel, with what it currently holds. */
@Composable
private fun ChannelRow(
    channel: ChannelSettings,
    onEditScanLimit: () -> Unit,
    onForget: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                channel.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Filter: ${channel.filter} · Scan limit: " +
                    "${AppPreferences.scanLimitLabel(channel.scanLimit)} files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        // Which channel each button belongs to is in the description rather than
        // left to position: a list of channels offers these same two controls once
        // per row, and read aloud they would otherwise be identical.
        IconButton(onClick = onEditScanLimit) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = "Change scan limit for ${channel.title}"
            )
        }
        IconButton(onClick = onForget) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Forget preferences for ${channel.title}"
            )
        }
    }
}
