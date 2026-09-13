package com.swarupbaral.smarttgdownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swarupbaral.smarttgdownloader.data.DownloadManager
import com.swarupbaral.smarttgdownloader.data.DownloadsMigration
import com.swarupbaral.smarttgdownloader.data.DownloadsStore
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.ui.screens.HistoryScreen
import com.swarupbaral.smarttgdownloader.ui.screens.HomeScreen
import com.swarupbaral.smarttgdownloader.ui.screens.LoginScreen
import com.swarupbaral.smarttgdownloader.ui.screens.SetupScreen
import com.swarupbaral.smarttgdownloader.ui.screens.SettingsScreen
import com.swarupbaral.smarttgdownloader.ui.theme.SmartTGTheme
import com.swarupbaral.smarttgdownloader.ui.theme.isAppDarkTheme
import com.swarupbaral.smarttgdownloader.update.UpdatePrompt

class MainActivity : ComponentActivity() {

    /**
     * A folder a notification asked to show, held until this activity is
     * actually resumed.
     *
     * The notification's "Open folder" action cannot start a file manager from
     * the background on Android 10+, so it starts this activity instead and the
     * folder is opened here, from the foreground.
     */
    private var pendingFolderOpen: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh start acts on the extra. On a configuration change the
        // same intent is delivered again, and reopening a folder the user has
        // already looked at and left would be a surprise.
        if (savedInstanceState == null) {
            pendingFolderOpen = intent?.getStringExtra(EXTRA_OPEN_FOLDER)
        }
        // Opaque dark launch surface: kills the white window flash on the
        // dark-first app and guarantees a themed backdrop even if composition
        // is slow (previously a WHITE background here made startup look wrong
        // on dark system themes).
        window.setBackgroundDrawableResource(R.color.launch_background)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppContainer.appPreferences.themeMode.collectAsState()
            val dynamicColor by AppContainer.appPreferences.dynamicColor.collectAsState()
            val darkTheme = isAppDarkTheme(themeMode)

            // Re-apply edge-to-edge whenever the theme flips so status bar
            // icons match the app theme (Light mode must get dark icons).
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) {
                        darkTheme
                    },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) {
                        darkTheme
                    }
                )
                onDispose { }
            }

            SmartTGTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StartupGate { SmartTGAppRoot() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The activity is single-top, so a second tap on the notification action
        // arrives here rather than through onCreate.
        setIntent(intent)
        pendingFolderOpen = intent.getStringExtra(EXTRA_OPEN_FOLDER)
    }

    /**
     * Opens a requested folder now that the app is on screen.
     *
     * Consumed exactly once, so returning to the app later does not reopen a
     * folder the user has already seen and left.
     */
    override fun onPostResume() {
        super.onPostResume()
        val folder = pendingFolderOpen ?: return
        pendingFolderOpen = null
        DownloadsStore.openFolder(this, folder)
    }

    companion object {
        const val EXTRA_OPEN_FOLDER = "com.swarupbaral.smarttgdownloader.extra.OPEN_FOLDER"

        /** Brings the app forward, carrying a folder to show once resumed. */
        fun folderIntent(context: Context, folder: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_FOLDER, folder)
            }
    }
}

/**
 * Holds back the first screen until TDLib has reported a real authorization
 * state, so a signed-in user goes straight to Home instead of watching the
 * intro screen flash past. (The old permission screen used to absorb that
 * pause; removing it exposed the flash.)
 *
 * Resolution is capped at a couple of seconds: a slow or offline network must
 * never strand the user on a spinner, so the app renders its normal first
 * screen regardless once the grace period is up.
 */
@Composable
private fun StartupGate(content: @Composable () -> Unit) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(2_500L) {
            AppContainer.repository.authState.first { state ->
                state !is TelegramRepository.AuthState.Idle &&
                    state !is TelegramRepository.AuthState.Connecting
            }
        }
        settled = true
    }

    if (settled) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading" }
            )
            Spacer(Modifier.height(12.dp))
            Text("Starting…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

private object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

private data class Tab(val route: String, val label: String)

private val MAIN_TABS = listOf(
    Tab(Routes.HOME, "Home"),
    Tab(Routes.HISTORY, "History")
)

/**
 * Compose root of the whole UI.
 *
 * Deliberately NOT called `SmartTGApp`: the [SmartTGApp] Application class
 * lives in the same package, and with both named `SmartTGApp` the call in
 * [MainActivity.onCreate]'s setContent resolved to the Application
 * *constructor* — it built a throwaway Application object, composed nothing,
 * and the app launched to a blank screen. Keep these two names distinct.
 */
@Composable
fun SmartTGAppRoot(repository: TelegramRepository = AppContainer.repository) {
    val navController = rememberNavController()
    val authState by repository.authState.collectAsState()
    val context = LocalContext.current

    // Resume a batch that a process kill interrupted. Waits for an authorized
    // state: resuming while TDLib has no client would just fail every file.
    LaunchedEffect(authState) {
        if (authState is TelegramRepository.AuthState.Ready) {
            DownloadManager.resumePendingWork(context)
        }
    }

    // Bring back the failures a previous process left behind, so the Retry card
    // is there after a restart instead of the reason being lost with the
    // process. Independent of sign-in: a failure is worth reporting either way.
    LaunchedEffect(Unit) {
        DownloadManager.restoreFailedState(context)
    }

    // One-time: move anything left in the pre-2.2.0-final Download/SmartTG tree
    // into the per-channel layout, so an update does not leave files sitting in
    // a folder the app no longer writes to. Cheap on every later start — it is
    // one flag read once it has nothing left to do.
    LaunchedEffect(Unit) {
        DownloadsMigration.runIfNeeded(context)
    }

    LaunchedEffect(authState) {
        // There is no permission gate any more: downloads are published
        // through MediaStore (see DownloadsStore), which needs no grant, so
        // the UI never blocks on storage access.
        val current = navController.currentDestination?.route
        when (authState) {
            is TelegramRepository.AuthState.Ready -> {
                if (current !in listOf(Routes.HOME, Routes.HISTORY, Routes.SETTINGS)) {
                    navController.navigate(Routes.HOME) { launchSingleTop = true }
                }
            }
            // WaitPhoneNumber matters too, otherwise a fresh install would sit
            // on the intro screen while Telegram is ready to take a number.
            is TelegramRepository.AuthState.WaitPhoneNumber,
            is TelegramRepository.AuthState.AwaitingCode,
            is TelegramRepository.AuthState.AwaitingPassword -> {
                if (current != Routes.LOGIN) {
                    navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                }
            }
            else -> Unit
        }
    }

    // Resolved once, from whatever TDLib has already told us: a returning user
    // opens on Home, anyone mid-login opens on Login, and only a fresh install
    // sees the intro. Computed here (not from a later state change) so the
    // NavHost never has to swap the start destination out from under itself.
    val startDestination = remember {
        when (repository.authState.value) {
            is TelegramRepository.AuthState.Ready -> Routes.HOME
            is TelegramRepository.AuthState.WaitPhoneNumber,
            is TelegramRepository.AuthState.AwaitingCode,
            is TelegramRepository.AuthState.AwaitingPassword -> Routes.LOGIN
            else -> Routes.SETUP
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == Routes.HOME || currentRoute == Routes.HISTORY

    // Silent update check once per app start, once authenticated.
    LaunchedEffect(authState) {
        if (authState is TelegramRepository.AuthState.Ready) {
            AppContainer.updateManager.checkForUpdate(manual = false)
        }
    }

    // One prompt surface for the whole app (dialogs + toasts).
    UpdatePrompt(AppContainer.updateManager)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    for (tab in MAIN_TABS) {
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                when (tab.route) {
                                    Routes.HOME ->
                                        Icon(Icons.Filled.Home, contentDescription = tab.label)
                                    else ->
                                        Icon(Icons.Filled.History, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.SETUP) {
                SetupScreen(
                    onContinue = { navController.navigate(Routes.LOGIN) { launchSingleTop = true } }
                )
            }
            composable(Routes.LOGIN) {
                LoginScreen()
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    }
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
