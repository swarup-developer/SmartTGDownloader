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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.style.TextOverflow
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.ChannelSettings
import com.swarupbaral.smarttgdownloader.data.DownloadNetwork
import com.swarupbaral.smarttgdownloader.data.DownloadsStore
import com.swarupbaral.smarttgdownloader.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val account by viewModel.accountLabel.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val autoCheckEnabled by viewModel.autoCheckEnabled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val defaultCategory by viewModel.defaultCategory.collectAsState()
    val downloadNetwork by viewModel.downloadNetwork.collectAsState()
    val scanLimit by viewModel.scanLimit.collectAsState()
    val channels by viewModel.channels.collectAsState()
    var pendingForgetAll by remember { mutableStateOf(false) }
    val checking = updateState is com.swarupbaral.smarttgdownloader.update.UpdateManager.UpdateState.Checking

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(
                "Account",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Signed in as $account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = viewModel::logOut,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Log out") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Smart TG Downloader v${viewModel.currentVersionName} (native Android rebuild)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ThemeMode.entries) {
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(24.dp))
            Text(
                "Default filter",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The app's filter options, used for any channel that has not been configured " +
                    "yet. Each channel then keeps its own filter, set from the Home screen.",
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
                    FilterChip(
                        selected = defaultCategory == category,
                        onClick = { viewModel.setDefaultCategory(category) },
                        label = { Text(category) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Default scan limit",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "How many files one analysis collects before it stops, counting from the most " +
                    "recent. Used for channels with nothing saved; each channel keeps its own " +
                    "limit, which can be raised from its own results panel.",
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
                        label = {
                            Text(
                                if (option <= 0) "All files"
                                else "${AppPreferences.scanLimitLabel(option)} files"
                            )
                        },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            stateDescription = if (isSelected) "Selected" else "Not selected"
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Channels",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (channels.isEmpty()) {
                    "Each channel you analyze remembers its own filter, range and scan " +
                        "limit. None remembered yet."
                } else {
                    "Each channel remembers its own filter, range and scan limit."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (channels.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                for (channel in channels) {
                    ChannelRow(
                        channel = channel,
                        onForget = { viewModel.forgetChannel(channel.chatId) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingForgetAll = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Forget all channels") }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Download connection",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
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
            Text(
                "Where downloads go",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
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

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = viewModel::checkForUpdates,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checking) "Checking…" else "Check for updates")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Done") }
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
                    }
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { pendingForgetAll = false }) { Text("Cancel") }
            }
        )
    }
}

/** One remembered channel, with what it currently holds. */
@Composable
private fun ChannelRow(channel: ChannelSettings, onForget: () -> Unit) {
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
        IconButton(onClick = onForget) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Forget preferences for ${channel.title}"
            )
        }
    }
}
