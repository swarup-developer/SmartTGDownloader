package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Decides whether a download may start right now.
 *
 * The check is deliberately a pre-flight one: it runs when the user asks for a
 * download, so the answer is immediate and can be explained in one sentence.
 * It is not a mid-transfer watchdog — a download already running when Wi-Fi
 * drops will finish on whatever connection is live.
 *
 * Reads only local connectivity state, so it is cheap enough to call from the
 * main thread.
 */
object NetworkGuard {

    sealed interface Status {
        data object Allowed : Status

        /** Wi-Fi-only is set and the active network is metered. */
        data object Metered : Status

        data object Offline : Status
    }

    fun status(context: Context, preference: DownloadNetwork): Status {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return Status.Offline
        val network = manager.activeNetwork ?: return Status.Offline
        val capabilities = manager.getNetworkCapabilities(network) ?: return Status.Offline
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return Status.Offline
        }
        if (preference == DownloadNetwork.WIFI_ONLY &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        ) {
            return Status.Metered
        }
        return Status.Allowed
    }

    /** User-facing reason, or null when the download may proceed. */
    fun blockedReason(status: Status): String? = when (status) {
        Status.Allowed -> null
        Status.Metered ->
            "Downloads are set to Wi-Fi only. Connect to Wi-Fi, or allow mobile data in Settings."
        Status.Offline ->
            "No internet connection. Connect to a network and try again."
    }

    fun blockedReason(context: Context, preference: DownloadNetwork): String? =
        blockedReason(status(context, preference))
}
