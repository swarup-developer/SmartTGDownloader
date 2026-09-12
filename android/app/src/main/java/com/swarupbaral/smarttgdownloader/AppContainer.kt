package com.swarupbaral.smarttgdownloader

import android.app.Application
import com.swarupbaral.smarttgdownloader.data.AppDatabase
import com.swarupbaral.smarttgdownloader.data.AppPreferences
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

    val historyDao: DownloadHistoryDao
        get() = database.downloadHistoryDao()

    fun init(app: Application) {
        if (!::appPreferences.isInitialized) {
            appPreferences = AppPreferences(app)
        }
        if (!::repository.isInitialized) {
            repository = TelegramRepository(app)
        }
        if (!::database.isInitialized) {
            database = AppDatabase.get(app)
        }
        if (!::updateManager.isInitialized) {
            updateManager = UpdateManager(app, appPreferences)
        }
    }
}
