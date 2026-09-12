package com.swarupbaral.smarttgdownloader.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Observes [UpdateManager.state] and renders the matching UI:
 *
 *  - Available   → "Update available" dialog (Update now / Later)
 *  - Downloading → dialog with a progress bar
 *  - Ready       → "Install" dialog that hands the APK to the system installer
 *  - UpToDate    → toast (manual checks only)
 *  - Failed      → toast (manual checks always surface it; auto checks too)
 */
@Composable
fun UpdatePrompt(updateManager: UpdateManager) {
    val state by updateManager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    when (val s = state) {
        is UpdateManager.UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = updateManager::dismiss,
                title = { Text("Update available") },
                text = {
                    Column {
                        Text(
                            "Smart TG Downloader ${s.versionName} is available " +
                                "(you have ${updateManager.currentVersionName})."
                        )
                        s.releaseNotes?.let { notes ->
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "What's new",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.semantics { heading() }
                            )
                            Spacer(Modifier.height(4.dp))
                            ReleaseNotes(cleanMarkdown(notes))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "The update is downloaded from GitHub and installed after you confirm.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { scope.launch { updateManager.downloadUpdate() } }) {
                        Text("Update now")
                    }
                },
                dismissButton = {
                    Row {
                        if (s.releaseUrl != null) {
                            TextButton(onClick = { openUrl(context, s.releaseUrl) }) {
                                Text("GitHub")
                            }
                        }
                        TextButton(onClick = updateManager::dismiss) { Text("Later") }
                    }
                }
            )
        }

        is UpdateManager.UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* downloading cannot be dismissed */ },
                title = { Text("Downloading update…") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { s.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Text(
                            "${formatBytes(s.downloadedBytes)} of ${formatBytes(s.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {}
            )
        }

        is UpdateManager.UpdateState.Ready -> {
            AlertDialog(
                onDismissRequest = updateManager::dismiss,
                title = { Text("Update downloaded") },
                text = { Text("Install ${s.versionName} now? The app will restart to apply it.") },
                confirmButton = {
                    Button(onClick = { updateManager.install(context) }) { Text("Install") }
                },
                dismissButton = {
                    TextButton(onClick = updateManager::dismiss) { Text("Later") }
                }
            )
        }

        is UpdateManager.UpdateState.UpToDate -> {
            if (s.manual) {
                ToastOnce("You're on the latest version.")
                updateManager.dismiss()
            }
        }

        is UpdateManager.UpdateState.Failed -> {
            // Silent (auto) checks fail quietly — only user-initiated checks
            // surface an error, so flaky networks don't nag on every launch.
            if (s.manual) {
                ToastOnce("Update check failed: ${s.message}")
            }
            updateManager.dismiss()
        }

        is UpdateManager.UpdateState.Checking,
        is UpdateManager.UpdateState.Idle -> Unit
    }
}

/** Shows a toast once per composition, then no-ops on recomposition. */
@Composable
private fun ToastOnce(message: String) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/**
 * Renders cleaned release notes line by line. Uppercase bracket section tags
 * ("[ADDED]", "[FIXED]:", …) become bold primary-colored headers; everything
 * else is body text. Height-capped and scrollable so long notes can't blow
 * up the dialog.
 */
@Composable
private fun ReleaseNotes(notes: String) {
    Column(
        modifier = Modifier
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
    ) {
        notes.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            val tag = sectionTagOf(line)
            when {
                tag != null -> {
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                line.isEmpty() -> if (index > 0) Spacer(Modifier.height(4.dp))
                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/** "Added" from a "[ADDED]" / "[ADDED]:" line; null for anything else. */
private fun sectionTagOf(line: String): String? =
    Regex("^\\[([A-Z]{3,})]?:?$").find(line)
        ?.groupValues?.get(1)
        ?.lowercase()
        ?.replaceFirstChar { it.uppercase() }

/** Opens [url] in an external browser; no-ops when nothing can handle it. */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/**
 * Minimal markdown-to-plain-text cleanup for release notes: fences collapse
 * to an ellipsis, links become "text (url)", images become their alt text,
 * heading hashes / emphasis markers / inline-code backticks are stripped,
 * and list dashes turn into bullets.
 */
internal fun cleanMarkdown(markdown: String): String =
    markdown
        .replace(Regex("```[\\s\\S]*?```"), "…")
        .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+)\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "• ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return "%.1f %s".format(value, units[idx])
}
