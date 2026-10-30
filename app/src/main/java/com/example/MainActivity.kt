package com.example

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.engine.JarvisHandsFreeEngine
import com.example.service.WakeWordForegroundService
import com.example.ui.screens.HistoryLogScreen
import com.example.ui.screens.OmniOrbScreen
import com.example.ui.screens.RoutinesScreen
import com.example.ui.screens.SettingsHubScreen
import com.example.ui.screens.VocabularyScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel

enum class OmniMainDestination(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Default.Mic, "nav_tab_home"),
    HISTORY("History", Icons.Default.History, "nav_tab_history"),
    ROUTINES("Routines", Icons.Default.AutoMode, "nav_tab_routines"),
    VOCABULARY("Memory", Icons.Default.School, "nav_tab_vocabulary"),
    SETTINGS("Settings", Icons.Default.Settings, "nav_tab_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.omnivoice.agentfix.AgentBridge.appContext = applicationContext
        enableEdgeToEdge()

        // Screen Wake-Lock persistence so OmniVoice stays awake during voice sessions
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        JarvisHandsFreeEngine.init(applicationContext)

        handleIntent(intent)

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            MyApplicationTheme(darkTheme = isDarkTheme) {
                MainScreen(
                    viewModel = viewModel,
                    onEnterPipMode = { enterJarvisPipMode() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        JarvisHandsFreeEngine.setFloatingPipActive(false)
        viewModel.omniLiveEngine.ensureSessionAliveOnResume()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.isLiveSessionActive.value) {
            enterJarvisPipMode()
        }
    }

    fun enterJarvisPipMode(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
                val entered = enterPictureInPictureMode(params)
                JarvisHandsFreeEngine.setFloatingPipActive(entered)
                entered
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_START_LISTENING", false) == true) {
            viewModel.startListeningForCommand()
        }
    }
}

@Composable
fun MainScreen(
    viewModel: VoiceAssistantViewModel,
    onEnterPipMode: () -> Boolean = { false }
) {
    val context = LocalContext.current
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val destinations = OmniMainDestination.entries

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            WakeWordForegroundService.start(context)
            viewModel.startListeningForCommand()
        }
    }

    LaunchedEffect(hasMicPermission) {
        if (hasMicPermission) {
            WakeWordForegroundService.start(context)
        }
    }

    if (selectedTabIndex != 0) {
        BackHandler {
            selectedTabIndex = 0
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        if (isWideScreen) {
            // Tablet / Foldable / Landscape: Canonical Side NavigationRail Layout
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
                androidx.compose.material3.NavigationRail(
                    containerColor = DarkSurface,
                    modifier = Modifier.testTag("omnivoice_nav_rail")
                ) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                    destinations.forEachIndexed { index, dest ->
                        val isSelected = selectedTabIndex == index
                        androidx.compose.material3.NavigationRailItem(
                            selected = isSelected,
                            onClick = { selectedTabIndex = index },
                            icon = {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label
                                )
                            },
                            label = {
                                Text(
                                    text = dest.label,
                                    fontSize = 11.sp
                                )
                            },
                            colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                selectedIconColor = Color(0xFF041019),
                                selectedTextColor = CyanPrimary,
                                indicatorColor = CyanPrimary,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    when (destinations[selectedTabIndex]) {
                        OmniMainDestination.HOME -> com.example.ui.screens.JarvisStageFlowScreen(
                            viewModel = viewModel,
                            onEnterPipMode = onEnterPipMode
                        )
                        OmniMainDestination.HISTORY -> HistoryLogScreen(viewModel = viewModel)
                        OmniMainDestination.ROUTINES -> RoutinesScreen(viewModel = viewModel)
                        OmniMainDestination.VOCABULARY -> VocabularyScreen(viewModel = viewModel)
                        OmniMainDestination.SETTINGS -> SettingsHubScreen(viewModel = viewModel)
                    }
                }
            }
        } else {
            // Handheld / Compact Screen: Bottom NavigationBar Layout
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar(
                        containerColor = DarkSurface,
                        windowInsets = WindowInsets.navigationBars,
                        modifier = Modifier.testTag("omnivoice_bottom_nav")
                    ) {
                        destinations.forEachIndexed { index, dest ->
                            val isSelected = selectedTabIndex == index
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectedTabIndex = index },
                                icon = {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = dest.label,
                                        fontSize = 11.sp
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF041019),
                                    selectedTextColor = CyanPrimary,
                                    indicatorColor = CyanPrimary,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (destinations[selectedTabIndex]) {
                        OmniMainDestination.HOME -> com.example.ui.screens.JarvisStageFlowScreen(
                            viewModel = viewModel,
                            onEnterPipMode = onEnterPipMode
                        )
                        OmniMainDestination.HISTORY -> HistoryLogScreen(viewModel = viewModel)
                        OmniMainDestination.ROUTINES -> RoutinesScreen(viewModel = viewModel)
                        OmniMainDestination.VOCABULARY -> VocabularyScreen(viewModel = viewModel)
                        OmniMainDestination.SETTINGS -> SettingsHubScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
