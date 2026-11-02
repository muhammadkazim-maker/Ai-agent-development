package com.example.ui.lockscreen

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VoiceState
import com.example.ui.components.VoiceOrb
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel

class LockScreenOverlayActivity : ComponentActivity() {

    private val viewModel: VoiceAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockScreenFlags()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    LockScreenAssistantContent(
                        viewModel = viewModel,
                        onDismiss = { finish() },
                        onRequestUnlock = { requestDeviceUnlock() }
                    )
                }
            }
        }
    }

    private fun configureLockScreenFlags() {
        // Phase 01 Security Policy: Keep Android's normal lock enabled; do not auto-dismiss keyguard on launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun requestDeviceUnlock() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    finish()
                }
            })
        }
    }
}

@Composable
fun LockScreenAssistantContent(
    viewModel: VoiceAssistantViewModel,
    onDismiss: () -> Unit,
    onRequestUnlock: () -> Unit
) {
    val voiceState by viewModel.voiceState.collectAsState()
    val liveTranscription by viewModel.liveTranscription.collectAsState()
    val spokenResponse by viewModel.spokenResponse.collectAsState()
    val audioRms by viewModel.audioRms.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()

    // Auto-listen when screen opens
    LaunchedEffect(Unit) {
        viewModel.startListeningForCommand()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DarkBackground,
                        Color(0xFF0F172A),
                        Color(0xFF030712)
                    )
                )
            )
            .padding(24.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF334155), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Screen Mode",
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "LOCKED MODE",
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Voice Passphrase Protected",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary
                )
            }
        }

        // Center Voice Orb & Interactive State
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VoiceOrb(
                voiceState = voiceState,
                audioRms = audioRms
            )

            Spacer(modifier = Modifier.height(28.dp))

            // State Badge
            val stateText = when (voiceState) {
                is VoiceState.ListeningForCommand -> "Listening for command..."
                is VoiceState.AwaitingPassphrase -> "Speak your security passphrase"
                is VoiceState.VerifyingVoice -> "Verifying voice signature..."
                is VoiceState.ProcessingAi -> "Understanding command..."
                is VoiceState.ConfirmingAction -> "Awaiting confirmation (Yes/No)"
                is VoiceState.ExecutingAction -> "Executing action..."
                is VoiceState.Speaking -> "Speaking response..."
                is VoiceState.RequestedUnverified -> "Requested / Unverified"
                is VoiceState.Error -> "Verification failed"
                else -> "Ready"
            }

            Text(
                text = stateText,
                color = when (voiceState) {
                    is VoiceState.AwaitingPassphrase, is VoiceState.RequestedUnverified -> Color(0xFFF59E0B)
                    is VoiceState.Error -> Color(0xFFEF4444)
                    else -> CyanPrimary
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Live Transcription / Spoken Text Card
            if (liveTranscription.isNotBlank() || spokenResponse.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.9f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (liveTranscription.isNotBlank()) {
                            Text(
                                text = "“$liveTranscription”",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (spokenResponse.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = spokenResponse,
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            if (lockoutSeconds > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Lockout active: $lockoutSeconds seconds remaining",
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom Action Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRequestUnlock,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock Phone", fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        if (voiceState is VoiceState.ListeningForCommand || voiceState is VoiceState.AwaitingPassphrase) {
                            viewModel.stopListening()
                        } else {
                            viewModel.startListeningForCommand()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (voiceState is VoiceState.ListeningForCommand) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Microphone",
                        tint = Color(0xFF003548),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (voiceState is VoiceState.ListeningForCommand) "Stop" else "Listen",
                        color = Color(0xFF003548),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
