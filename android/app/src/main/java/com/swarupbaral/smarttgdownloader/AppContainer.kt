package com.swarupbaral.smarttgdownloader

import android.app.Application
import com.swarupbaral.smarttgdownloader.data.AppDatabase
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.ChannelPreferenceStore
import com.swarupbaral.smarttgdownloader.data.DownloadHistoryDao
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.update.UpdateManager

class SmartTGApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}

/** Minimal DI container: one instance of each app-scoped service per process. */
object AppContainer {
    lateinit var repository: TelegramRepository
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var updateManager: UpdateManager
        private set
    lateinit var appPreferences: AppPreferences
        private set
    lateinit var channelPreferences: ChannelPreferenceStore
        private set

    val historyDao: DownloadHistoryDao
        get() = database.downloadHistoryDao()

    fun init(app: Application) {
        if (!::appPreferences.isInitialized) {
            appPreferences = AppPreferences(app)
        }
        if (!::channelPreferences.isInitialized) {
            channelPreferences = ChannelPreferenceStore(app)
        }
        if (!::repository.isInitialized) {
            repository = TelegramRepository(app)
            // Bring TDLib up at launch. Nothing else calls start() (only the
            // Login screen's Retry does), so without this the auth state sat on
            // Idle forever and the login screen rendered no phone/OTP fields.
            // start() is idempotent and only launches a coroutine.
            repository.start()
        }
        if (!::database.isInitialized) {
            database = AppDatabase.get(app)
        }
        if (!::updateManager.isInitialized) {
            updateManager = UpdateManager(app, appPreferences)
        }
    }
}
