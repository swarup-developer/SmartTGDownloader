package com.swarupbaral.smarttgdownloader.ui.screens

import android.app.Activity
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.ThemeMode
import com.swarupbaral.smarttgdownloader.permissions.StoragePermissions

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
    val storageGranted by viewModel.storageGranted.collectAsState()
    val checking = updateState is com.swarupbaral.smarttgdownloader.update.UpdateManager.UpdateState.Checking

    val activity = LocalContext.current as Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check the storage grant whenever this screen resumes: if the user
    // just came back from the system all-files page, the status card and the
    // PermissionsScreen-style behavior stay truthful without a restart.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                StoragePermissions.refresh(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        StoragePermissions.refresh(activity)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                "Downloads",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pre-selected after analyzing a link",
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
                "Storage",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("All files access", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (storageGranted) {
                            "Granted — downloads save to your Download folder"
                        } else {
                            "Required before downloads can be saved"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Icon(
                    imageVector = if (storageGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = if (storageGranted) "Granted" else "Not granted",
                    tint = if (storageGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (StoragePermissions.needsLegacyRuntimeRequest) {
                        StoragePermissions.requestLegacyStorage(activity)
                    } else {
                        StoragePermissions.openAllFilesSettings(activity)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (StoragePermissions.needsLegacyRuntimeRequest) "Manage storage access"
                    else "Open all-files access settings"
                )
            }

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
}
