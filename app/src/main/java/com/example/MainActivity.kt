package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.angi.ui.chat.ChatScreen
import com.example.angi.ui.chat.ChatViewModel
import com.example.angi.ui.console.ConsoleScreen
import com.example.angi.ui.console.ConsoleViewModel
import com.example.angi.ui.diagnostics.DiagnosticsScreen
import com.example.angi.ui.diagnostics.DiagnosticsViewModel
import com.example.angi.ui.models.ModelManagerScreen
import com.example.angi.ui.models.ModelViewModel
import com.example.angi.ui.settings.SettingsScreen
import com.example.angi.ui.settings.SettingsViewModel
import com.example.angi.ui.tools.ToolsScreen
import com.example.angi.ui.tools.ToolsViewModel
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceElevated
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextTertiary
import com.example.ui.theme.MyApplicationTheme

enum class AngiNavDestination(
    val title: String,
    val icon: ImageVector,
    val testTag: String
) {
    CHAT("Chat", Icons.Default.Chat, "nav_tab_chat"),
    MODELS("Models", Icons.Default.Storage, "nav_tab_models"),
    TOOLS("Tools", Icons.Default.Build, "nav_tab_tools"),
    CONSOLE("Console", Icons.Default.Build, "nav_tab_console"),
    SETTINGS("Settings", Icons.Default.Tune, "nav_tab_settings"),
    DIAGNOSTICS("NPU Diag", Icons.Default.Memory, "nav_tab_diag")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AngiMainApp()
            }
        }
    }
}

@Composable
fun AngiMainApp() {
    var currentDestination by remember { mutableStateOf(AngiNavDestination.CHAT) }

    val chatViewModel: ChatViewModel = viewModel()
    val modelViewModel: ModelViewModel = viewModel()
    val toolsViewModel: ToolsViewModel = viewModel()
    val consoleViewModel: ConsoleViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val diagnosticsViewModel: DiagnosticsViewModel = viewModel()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AngiDarkBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AngiDarkSurface)
            ) {
                Text(
                    text = "ANGI v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    fontSize = 10.sp,
                    color = AngiTextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp)
                        .testTag("app_version_footer")
                )
                NavigationBar(
                    containerColor = AngiDarkSurface,
                    tonalElevation = 6.dp
                ) {
                AngiNavDestination.values().forEach { destination ->
                    val isSelected = currentDestination == destination
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            currentDestination = destination
                            if (destination == AngiNavDestination.CHAT) {
                                chatViewModel.refreshActiveModel()
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.title
                            )
                        },
                        label = {
                            Text(
                                text = destination.title,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AngiDarkBackground,
                            selectedTextColor = AngiPrimary,
                            indicatorColor = AngiPrimary,
                            unselectedIconColor = AngiTextTertiary,
                            unselectedTextColor = AngiTextTertiary
                        ),
                        modifier = Modifier.testTag(destination.testTag)
                    )
                }
            }
        }
    }
    ) { innerPadding ->
        when (currentDestination) {
            AngiNavDestination.CHAT -> {
                ChatScreen(
                    viewModel = chatViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            AngiNavDestination.MODELS -> {
                ModelManagerScreen(
                    viewModel = modelViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            AngiNavDestination.TOOLS -> {
                ToolsScreen(
                    viewModel = toolsViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            AngiNavDestination.CONSOLE -> {
                ConsoleScreen(
                    viewModel = consoleViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            AngiNavDestination.SETTINGS -> {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            AngiNavDestination.DIAGNOSTICS -> {
                DiagnosticsScreen(
                    viewModel = diagnosticsViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
