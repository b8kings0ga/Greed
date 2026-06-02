package com.excp.podroid.ui.navigation

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.activity.compose.LocalActivity
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.excp.podroid.ui.screens.launcher.LauncherScreen
import com.excp.podroid.ui.screens.settings.SettingsScreen

object Routes {
    const val LAUNCHER      = "launcher"
    const val SETTINGS      = "settings"
}

@Composable
fun PodroidNavGraph(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController = rememberNavController(),
) {
    val startDestination = Routes.LAUNCHER

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.LAUNCHER) {
            LauncherScreen(
                windowSizeClass = windowSizeClass,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.SETTINGS) {
            val activity = LocalActivity.current
            val onLanguageChanged = remember(activity) {
                { activity?.recreate() ?: Unit }
            }
            SettingsScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.LAUNCHER) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onLanguageChanged = onLanguageChanged,
            )
        }
    }
}
