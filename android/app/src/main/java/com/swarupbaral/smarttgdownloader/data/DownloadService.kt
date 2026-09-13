package com.swarupbaral.smarttgdownloader.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.swarupbaral.smarttgdownloader.MainActivity
import com.swarupbaral.smarttgdownloader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps [DownloadManager] alive while the user is elsewhere, and mirrors the
 * run into a progress notification.
 *
 * This is a plain foreground service rather than a WorkManager job: the work is
 * already-started, latency-sensitive transfer driven by a live TDLib client,
 * and WorkManager's constraint/retry scheduling would add a dependency to model
 * something it is not designed for. A foreground service is also the only thing
 * that actually guarantees the process survives the user leaving the app.
 *
 * Started only from [DownloadManager.enqueue] (i.e. from the UI), so it is
 * never launched from the background and cannot hit the Android 12+ background
 * start restrictions. `START_NOT_STICKY`: an interrupted batch resumes when the
 * app is next opened rather than springing back to life invisibly.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    /** onStartCommand can arrive repeatedly; only one observer should exist. */
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadManager.cancel(applicationContext)
            return START_NOT_STICKY
        }

        // Retry is a user action from the completion notification, so it runs
        // inside the same foreground path below rather than waking anything up
        // from the background. Returning early is safe because the retry itself
        // starts the service again through `enqueue`.
        if (intent?.action == ACTION_RETRY) {
            DownloadManager.retryFailed(applicationContext)
        }

        // Foreground FIRST: the system kills a started service that has not
        // posted its notification within a few seconds.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(DownloadManager.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (!DownloadManager.hasPendingWork(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        DownloadManager.launchRun(applicationContext)
        observeRun()
        return START_NOT_STICKY
    }

    /**
     * Mirrors the run into the notification, then gets out of the way once the
     * run ends. Idempotent: `onStartCommand` can arrive again while running
     * (a second "Download" tap tops the queue up).
     */
    private fun observeRun() {
        if (observing) return
        observing = true
        scope.launch {
            DownloadManager.state.collect { state ->
                when {
                    state.isRunning -> {
                        holdWakeLock()
                        postNotification(buildProgressNotification(state))
                    }
                    // IDLE is ignored: it is also the very first emission, and
                    // reacting to it would stop the service before it starts.
                    state.isTerminal -> {
                        releaseWakeLock()
                        // Detached rather than removed, so the completion summary
                        // stays readable in the shade — it is dismissible.
                        postNotification(buildFinishedNotification(state))
                        ServiceCompat.stopForeground(
                            this@DownloadService,
                            ServiceCompat.STOP_FOREGROUND_DETACH
                        )
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun postNotification(notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        // Notification permission can be denied on Android 13+. The service is
        // still valid — the notification simply will not be shown.
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun buildProgressNotification(state: DownloadManager.RunState): Notification {
        val title = when {
            // Packing is local disk work, so progress is reported as files
            // added to the archive rather than as a download.
            state.packing -> "Creating ZIP"
            state.total > 1 ->
                "Downloading ${(state.completed + 1).coerceAtMost(state.total)} of ${state.total}"
            else -> "Downloading"
        }
        return baseBuilder()
            .setContentTitle(title)
            .setContentText(state.currentName ?: "Preparing…")
            .setProgress(100, (state.fraction * 100).toInt(), false)
            .setOngoing(true)
            .addAction(0, "Cancel", cancelAction())
            .build()
    }

    private fun buildFinishedNotification(state: DownloadManager.RunState): Notification {
        val stopped = state.phase == DownloadManager.Phase.FAILED ||
            state.phase == DownloadManager.Phase.CANCELLED
        val builder = baseBuilder()
            .setContentTitle(
                when {
                    state.failed > 0 -> "Download finished with errors"
                    stopped -> "Download stopped"
                    else -> "Download complete"
                }
            )
            .setContentText(state.message ?: "")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
        // Offer the retry where the failure is reported, so it does not depend
        // on the user reopening the app to find a button.
        if (state.failed > 0) {
            builder.addAction(0, "Retry failed", retryAction())
        }
        // "Where did my files go?" answered from the notification, aimed at the
        // channel folder the batch actually landed in. Only offered once a file
        // really was saved — the folder exists from that moment.
        state.lastSavedFolder?.let { folder ->
            builder.addAction(0, "Open folder", openFolderAction(folder))
        }
        return builder.build()
    }

    /**
     * Opens the folder from the notification.
     *
     * Routes through [MainActivity] rather than starting a file manager
     * directly: a notification action may not start another app's activity from
     * the background on Android 10+, and this app's own activity is the case the
     * platform does allow. MainActivity opens the folder once it is resumed, so
     * the launch happens from the foreground.
     */
    private fun openFolderAction(folder: String): PendingIntent = PendingIntent.getActivity(
        this,
        3,
        MainActivity.folderIntent(this, folder),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            // Show immediately on Android 12+ instead of deferring by 10s.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelAction(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun retryAction(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, DownloadService::class.java).setAction(ACTION_RETRY),
        PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * A partial wake lock keeps the CPU awake through the transfer. Without it
     * the device can suspend with the screen off and the download stalls —
     * exactly the case this service exists to cover. Always released in
     * [onDestroy], so a killed service cannot strand it.
     */
    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            // LOW: progress must never make a sound or vibrate.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while media downloads are running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.swarupbaral.smarttgdownloader.action.CANCEL_DOWNLOAD"
        const val ACTION_RETRY = "com.swarupbaral.smarttgdownloader.action.RETRY_FAILED"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_LOCK_TAG = "SmartTGDownloader:downloads"
    }
}
