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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.engine.JarvisHandsFreeEngine
import com.example.service.WakeWordForegroundService
import com.example.ui.screens.JarvisStageFlowScreen
import com.example.ui.screens.SettingsHubScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel

enum class OmniMainDestination(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("Neural Core", Icons.Default.Mic, "nav_tab_home"),
    SETTINGS("System Config", Icons.Default.Settings, "nav_tab_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.omnivoice.agentfix.AgentBridge.appContext = applicationContext
        enableEdgeToEdge()

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
    var showFullSettingsHub by rememberSaveable { mutableStateOf(false) }

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

    if (showFullSettingsHub) {
        BackHandler {
            showFullSettingsHub = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("omnivoice_revamped_root")
        ) {
            JarvisStageFlowScreen(
                viewModel = viewModel,
                onEnterPipMode = onEnterPipMode,
                onOpenAdvancedSettingsHub = { showFullSettingsHub = true }
            )

            AnimatedVisibility(
                visible = showFullSettingsHub,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                        .statusBarsPadding()
                        .testTag("revamped_settings_hub_overlay")
                ) {
                    Surface(
                        color = DarkSurface,
                        border = BorderStroke(1.dp, DarkSurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showFullSettingsHub = false },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(CyanPrimary.copy(alpha = 0.12f), CircleShape)
                                        .border(1.dp, CyanPrimary.copy(alpha = 0.35f), CircleShape)
                                        .testTag("btn_back_from_settings_hub")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Neural Core",
                                        tint = CyanPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "AETHER-X // SYSTEM MATRIX",
                                        color = EmeraldTertiary,
                                        fontSize = 10.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.4.sp
                                    )
                                    Text(
                                        text = "Neural Core & OS Configuration",
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Surface(
                                color = EmeraldTertiary.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, EmeraldTertiary.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = "KERNEL v4.8",
                                    color = EmeraldTertiary,
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        SettingsHubScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
