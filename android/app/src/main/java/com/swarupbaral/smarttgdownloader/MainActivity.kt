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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import com.swarupbaral.smarttgdownloader.ui.screens.SettingsSection
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

/**
 * Where the menu leads: the screens that show it.
 *
 * The intro and the login screen are not in it, because there is nothing behind
 * them to navigate to yet — the menu's swipe is disabled there rather than
 * opening a sheet of destinations the user cannot use.
 */
private val MENU_ROUTES = setOf(Routes.HOME, Routes.HISTORY, Routes.SETTINGS)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val MAIN_TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.HISTORY, "History", Icons.Filled.History)
)

/** A menu icon per Settings group, so the list reads as places, not as words. */
private fun sectionIcon(section: SettingsSection): ImageVector = when (section) {
    SettingsSection.ACCOUNT -> Icons.Filled.Person
    SettingsSection.APPEARANCE -> Icons.Filled.Palette
    SettingsSection.ANALYSIS -> Icons.Filled.Tune
    SettingsSection.DOWNLOADS -> Icons.Filled.Download
    SettingsSection.CHANNELS -> Icons.Filled.Subscriptions
    SettingsSection.UPDATES -> Icons.Filled.Update
}

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
                if (current !in MENU_ROUTES) {
                    navController.navigate(Routes.HOME) { launchSingleTop = true }
                }
            }
            // WaitPhoneNumber matters too, otherwise a fresh install would sit
            // on the intro screen while Telegram is ready to take a number.
            is TelegramRepository.AuthState.WaitPhoneNumber,
            is TelegramRepository.AuthState.AwaitingCode,
            is TelegramRepository.AuthState.AwaitingPassword -> {
                if (current != Routes.LOGIN) {
                    navController.navigate(Routes.LOGIN) {
                        // Login replaces the app's screens rather than stacking on
                        // top of them. Signing out made the difference visible:
                        // pushed on top, Back returned to a Home screen with no
                        // session behind it, where Analyze can only fail.
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
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

    // The menu, and the ways it can be opened.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
    val menuAvailable = currentRoute != null && currentRoute in MENU_ROUTES

    // Which Settings group is showing, owned here rather than inside the screen so
    // the menu and the screen cannot disagree: the item the menu marks as current
    // is the group the screen is actually on, including when the group was changed
    // from the screen's own section row.
    //
    // Held as the section's key rather than as the enum itself. An enum is not one
    // of the types rememberSaveable can put in a Bundle, so saving the enum would
    // have failed the moment the state was saved — on a rotation, say — and the
    // key round-trips through the tested lookup in SettingsSection.fromKey.
    var settingsSectionKey by rememberSaveable { mutableStateOf(SettingsSection.DEFAULT.key) }
    val settingsSection = SettingsSection.fromKey(settingsSectionKey)

    // Signing out is offered from the menu, which is reachable from every screen,
    // so the confirmation and the action are owned here rather than by a screen
    // the user may not be looking at.
    var pendingSignOut by remember { mutableStateOf(false) }

    // Silent update check once per app start, once authenticated.
    LaunchedEffect(authState) {
        if (authState is TelegramRepository.AuthState.Ready) {
            AppContainer.updateManager.checkForUpdate(manual = false)
        }
    }

    // One prompt surface for the whole app (dialogs + toasts).
    UpdatePrompt(AppContainer.updateManager)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = menuAvailable,
        drawerContent = {
            AppMenu(
                currentRoute = currentRoute,
                currentSection = settingsSection,
                onOpenTab = { route ->
                    scope.launch { drawerState.close() }
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    pendingSignOut = true
                },
                onOpenSettings = { section ->
                    scope.launch { drawerState.close() }
                    settingsSectionKey = section.key
                    navController.navigate(Routes.SETTINGS) {
                        // One Settings entry, whichever group it shows: the group
                        // is a parameter of that screen, not a screen of its own,
                        // so leaving Settings returns the user where they were
                        // rather than through every group they looked at.
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    ) {
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
                                    // The label sits right under the icon and the
                                    // item folds it into its own name, so the icon
                                    // says nothing itself — otherwise the tab is
                                    // announced as "Home, Home".
                                    Icon(tab.icon, contentDescription = null)
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
                        onContinue = {
                            navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                        }
                    )
                }
                composable(Routes.LOGIN) {
                    LoginScreen()
                }
                composable(Routes.HOME) {
                    HomeScreen(onOpenMenu = openMenu)
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(onOpenMenu = openMenu)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        section = settingsSection,
                        onSelectSection = { settingsSectionKey = it.key },
                        onOpenMenu = openMenu,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    if (pendingSignOut) {
        SignOutDialog(
            onConfirm = {
                pendingSignOut = false
                // Fire and forget: ending the session is a single TDLib call, and
                // the auth-state effect above moves the UI to the login screen as
                // soon as Telegram confirms it. A failure leaves the user signed
                // in — the state they can try again from — so there is nothing
                // here worth a message.
                scope.launch { runCatching { AppContainer.repository.logOut() } }
            },
            onDismiss = { pendingSignOut = false }
        )
    }
}

/**
 * The menu: every destination in the app, in one list.
 *
 * Home and History come first, because they are where the app's work is done,
 * and Settings follows group by group rather than as a single entry: each group
 * is then one tap from anywhere, instead of a tap plus a section choice.
 *
 * The items carry labels only. Icons here are decorative — every row already
 * says its own name — so they are given no description, and the drawer is
 * announced by its pane title rather than as an untitled list of rows.
 */
@Composable
private fun AppMenu(
    currentRoute: String?,
    currentSection: SettingsSection,
    onOpenTab: (String) -> Unit,
    onOpenSettings: (SettingsSection) -> Unit,
    onSignOut: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.semantics { paneTitle = "Menu" }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            Text(
                "Smart TG Downloader",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .semantics { heading() }
            )
            Text(
                "Download from any Telegram link",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            for (tab in MAIN_TABS) {
                NavigationDrawerItem(
                    label = { Text(tab.label) },
                    selected = currentRoute == tab.route,
                    onClick = { onOpenTab(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text(
                "Settings",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 28.dp, top = 12.dp, bottom = 4.dp)
                    .semantics { heading() }
            )
            for (section in SettingsSection.entries) {
                NavigationDrawerItem(
                    label = { Text(section.label) },
                    selected = currentRoute == Routes.SETTINGS && currentSection == section,
                    onClick = { onOpenSettings(section) },
                    icon = { Icon(sectionIcon(section), contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }

            // Last, below the destinations and behind a divider, because it is not
            // one: it ends the session rather than going somewhere. Putting it
            // here rather than in Settings also means it is reachable from every
            // screen, which is where a user looks when they want out.
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            NavigationDrawerItem(
                label = { Text("Sign out") },
                // Always unselected: nothing is "current" about it. The drawer item
                // requires the flag, and marking it either way would say something
                // untrue about where the user is.
                selected = false,
                onClick = onSignOut,
                icon = {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The one sign-out confirmation, shared by everything that asks for it.
 *
 * Moved out of the Settings screen's Account group: the request can now come from
 * the menu, which is reachable from any screen, so the dialog cannot live inside
 * a screen the user may not be on. There is exactly one of these, so the words
 * cannot drift between two copies.
 *
 * The confirm button repeats the action rather than saying "OK", so the answer
 * is never given to a question the user has not re-read.
 */
@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log out?") },
        text = {
            Text(
                "Signs out of Telegram and clears the stored session. Your downloads " +
                    "and history stay on this device, and you will need your phone " +
                    "number and a new login code to sign back in."
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Log out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
