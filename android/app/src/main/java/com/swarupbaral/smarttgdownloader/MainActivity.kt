package com.swarupbaral.smarttgdownloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.ui.screens.HistoryScreen
import com.swarupbaral.smarttgdownloader.ui.screens.HomeScreen
import com.swarupbaral.smarttgdownloader.ui.screens.LoginScreen
import com.swarupbaral.smarttgdownloader.ui.screens.PermissionsScreen
import com.swarupbaral.smarttgdownloader.ui.screens.SetupScreen
import com.swarupbaral.smarttgdownloader.ui.screens.SettingsScreen
import com.swarupbaral.smarttgdownloader.ui.theme.SmartTGTheme
import com.swarupbaral.smarttgdownloader.ui.theme.isAppDarkTheme
import com.swarupbaral.smarttgdownloader.update.UpdatePrompt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    SmartTGApp()
                }
            }
        }
    }
}

private object Routes {
    const val PERMISSIONS = "permissions"
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

@Composable
fun SmartTGApp(repository: TelegramRepository = AppContainer.repository) {
    val navController = rememberNavController()
    val authState by repository.authState.collectAsState()

    LaunchedEffect(authState) {
        when (authState) {
            is TelegramRepository.AuthState.Ready -> {
                // PERMISSIONS is the gate: never yank the user off it — the
                // permission screen forwards to SETUP/HOME once granted.
                if (navController.currentDestination?.route !in
                    listOf(Routes.HOME, Routes.HISTORY, Routes.SETTINGS, Routes.PERMISSIONS)
                ) {
                    navController.navigate(Routes.HOME) { launchSingleTop = true }
                }
            }
            is TelegramRepository.AuthState.AwaitingCode,
            is TelegramRepository.AuthState.AwaitingPassword -> {
                if (navController.currentDestination?.route != Routes.LOGIN &&
                    navController.currentDestination?.route != Routes.PERMISSIONS
                ) {
                    navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                }
            }
            else -> Unit
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
            startDestination = Routes.PERMISSIONS,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.PERMISSIONS) {
                PermissionsScreen(
                    onAllGranted = {
                        navController.navigate(Routes.SETUP) {
                            popUpTo(Routes.PERMISSIONS) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
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
