package com.swarupbaral.smarttgdownloader.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.swarupbaral.smarttgdownloader.permissions.StoragePermissions

/**
 * Dedicated permission screen: explains why the app needs storage access,
 * requests every missing grant, and blocks progress until storage is ready.
 *
 * Storage grant paths (per API level):
 *  - Android 11+ : All-files access via the system settings page
 *  - Android 8–10: READ/WRITE_EXTERNAL_STORAGE runtime dialog
 *  - Android 13+ : POST_NOTIFICATIONS runtime dialog
 */
@Composable
fun PermissionsScreen(onAllGranted: () -> Unit) {
    val activity = LocalContext.current as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val status by StoragePermissions.status.collectAsState()

    // Live status: refresh on every resume so returning from the system
    // settings page (Android 11+ grant path) updates the screen instantly.
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

    LaunchedEffect(status.allFilesGranted) {
        if (status.allFilesGranted) onAllGranted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Permissions needed",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Smart TG Downloader saves the media you download into your " +
                "device's Download folder. Grant access so your files appear " +
                "where you can find them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(24.dp))

        PermissionCard(
            title = "All files access",
            description = "Needed to save downloads into your Download folder " +
                "and keep them organized by channel.",
            granted = status.allFilesGranted
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (StoragePermissions.needsLegacyRuntimeRequest) {
                    StoragePermissions.requestLegacyStorage(activity)
                } else {
                    StoragePermissions.openAllFilesSettings(activity)
                }
            },
            enabled = !status.allFilesGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (StoragePermissions.needsLegacyRuntimeRequest) "Allow storage access"
                else "Allow access in Settings"
            )
        }

        if (StoragePermissions.needsNotificationsRequest) {
            Spacer(Modifier.height(24.dp))
            PermissionCard(
                title = "Notifications",
                description = "Shows progress while downloads are running.",
                granted = status.notificationsGranted
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { StoragePermissions.requestNotifications(activity) },
                enabled = !status.notificationsGranted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (status.notificationsGranted) "Allowed" else "Allow notifications")
            }
        }
    }
}

@Composable
private fun PermissionCard(title: String, description: String, granted: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = if (granted) "Granted" else "Not granted",
                tint = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
