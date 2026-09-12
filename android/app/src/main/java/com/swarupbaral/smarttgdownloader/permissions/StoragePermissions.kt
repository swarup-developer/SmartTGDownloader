package com.swarupbaral.smarttgdownloader.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central authority for every runtime permission the app needs.
 *
 * Storage strategy (researched against developer.android.com):
 *  - Android 11+ (R): the app is a bulk media downloader that must write
 *    arbitrary files into the user's public Download folder, which scoped
 *    storage forbids — so it uses All-files access (MANAGE_EXTERNAL_STORAGE),
 *    granted from a dedicated system settings page, not a runtime dialog.
 *  - Android 8–10: classic READ/WRITE_EXTERNAL_STORAGE runtime prompt
 *    (+ requestLegacyExternalStorage on 10 for direct path writes).
 *  - Android 13+ (T): POST_NOTIFICATIONS runtime prompt so download progress
 *    notifications can be shown.
 */
object StoragePermissions {

    data class Status(
        val allFilesGranted: Boolean,
        val notificationsGranted: Boolean
    )

    private val _status = MutableStateFlow(Status(allFilesGranted = false, notificationsGranted = false))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Android 10 and below use the classic runtime storage prompt. */
    val needsLegacyRuntimeRequest: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** Android 13+ gates notifications behind a runtime permission. */
    val needsNotificationsRequest: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun allFilesGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    /** Re-reads current grants; call on resume and after any request returns. */
    fun refresh(context: Context) {
        _status.value = Status(
            allFilesGranted = allFilesGranted(context),
            notificationsGranted = notificationsGranted(context)
        )
    }

    /**
     * Android 11+: opens THIS app's page in system settings where the user
     * flips "Allow access to manage all files" (the only grant path for
     * MANAGE_EXTERNAL_STORAGE — there is no dialog API). Falls back to the
     * generic all-files list if the deep link is unavailable.
     */
    fun openAllFilesSettings(activity: Activity) {
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(appSpecific) }.onFailure {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Android 9 and below (also 10 when legacy storage applies). */
    fun requestLegacyStorage(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            LEGACY_STORAGE_REQUEST
        )
    }

    fun requestNotifications(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATIONS_REQUEST
        )
    }

    const val LEGACY_STORAGE_REQUEST = 4101
    const val NOTIFICATIONS_REQUEST = 4102
}
