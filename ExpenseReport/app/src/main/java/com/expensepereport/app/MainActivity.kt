package com.expensepereport.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.expensepereport.app.data.AppSettingsRepository
import com.expensepereport.app.data.SupabaseService
import com.expensepereport.app.ui.screens.ExportScreen
import com.expensepereport.app.ui.screens.NewExpenseScreen
import com.expensepereport.app.ui.screens.RecordsScreen
import com.expensepereport.app.ui.screens.ReportsScreen
import com.expensepereport.app.ui.screens.SettingsScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object NewExpense : Screen("new_expense", "Nuova Spesa", Icons.Filled.Add)
    object Records : Screen("records", "Registri", Icons.AutoMirrored.Filled.List)
    object Reports : Screen("reports", "Report", Icons.Filled.BarChart)
    object Export : Screen("export", "Export", Icons.Filled.Share)
    object Settings : Screen("settings", "Impostazioni", Icons.Filled.Settings)
}

@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (useDarkTheme) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsRepository = AppSettingsRepository(applicationContext)

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = contentColorFor(MaterialTheme.colorScheme.background)
                ) {
                    val supabaseUrl by settingsRepository.supabaseUrlFlow.collectAsState(initial = AppSettingsRepository.DEFAULT_SUPABASE_URL)
                    val supabaseKey by settingsRepository.supabaseKeyFlow.collectAsState(initial = AppSettingsRepository.DEFAULT_SUPABASE_KEY)

                    val supabaseService = remember(supabaseUrl, supabaseKey) {
                        SupabaseService(supabaseUrl, supabaseKey)
                    }

                    val navController = rememberNavController()
                    val items = listOf(
                        Screen.NewExpense,
                        Screen.Records,
                        Screen.Reports,
                        Screen.Export,
                        Screen.Settings
                    )

                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                val navBackStackEntry by navController.currentBackStackEntryAsState()
                                val currentRoute = navBackStackEntry?.destination?.route

                                items.forEach { screen ->
                                    NavigationBarItem(
                                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                                        label = { Text(screen.title) },
                                        selected = currentRoute == screen.route,
                                        onClick = {
                                            if (currentRoute != screen.route) {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.startDestinationId) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = Screen.NewExpense.route,
                            modifier = Modifier.padding(innerPadding)
                        ) {
                            composable(Screen.NewExpense.route) {
                                NewExpenseScreen(supabaseService = supabaseService)
                            }
                            composable(Screen.Records.route) {
                                RecordsScreen(supabaseService = supabaseService)
                            }
                            composable(Screen.Reports.route) {
                                ReportsScreen(supabaseService = supabaseService)
                            }
                            composable(Screen.Export.route) {
                                ExportScreen(
                                    supabaseService = supabaseService,
                                    settingsRepository = settingsRepository
                                )
                            }
                            composable(Screen.Settings.route) {
                                SettingsScreen(settingsRepository = settingsRepository)
                            }
                        }
                    }
                }
            }
        }
    }
}
