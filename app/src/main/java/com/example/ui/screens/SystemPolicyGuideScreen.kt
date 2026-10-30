package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary

data class PolicyRule(
    val title: String,
    val whatAndroidForbids: String,
    val securityRationale: String,
    val bestWorkaround: String
)

@Composable
fun SystemPolicyGuideScreen() {
    val policies = listOf(
        PolicyRule(
            title = "1. Background Continuous Microphone Access",
            whatAndroidForbids = "Since Android 11 (API 30), standard background apps and services cannot record audio or access the microphone when no activity is visible.",
            securityRationale = "Prevents rogue applications from covertly eavesdropping on user conversations in the background.",
            bestWorkaround = "Implement an Android Foreground Service with explicit 'foregroundServiceType=\"microphone\"' and a persistent ongoing status notification, paired with Battery Optimization exemption."
        ),
        PolicyRule(
            title = "2. Programmatically Unlocking Keyguard / PIN / Biometrics",
            whatAndroidForbids = "Android strictly prohibits third-party apps from bypassing or programmatically unlocking the device lock screen without the user's PIN, pattern, or biometric hardware.",
            securityRationale = "Protects the phone's hardware cryptographic enclave and user privacy if the device is lost or stolen.",
            bestWorkaround = "Use 'setShowWhenLocked(true)' and 'setTurnScreenOn(true)' to render a secure voice overlay window over the lock screen. For restricted actions (banking/settings), call 'KeyguardManager.requestDismissKeyguard()' to present the system PIN prompt."
        ),
        PolicyRule(
            title = "3. Airplane Mode & Mobile Data Toggling",
            whatAndroidForbids = "Since Android 4.2+, toggling Airplane Mode or Cellular Data directly via API requires 'android.permission.MODIFY_PHONE_STATE' or system signature privileges.",
            securityRationale = "Prevents malicious apps from cutting off emergency communications or incurring unintended data carrier charges.",
            bestWorkaround = "Launch the system Quick Settings or Settings panel ('ACTION_AIRPLANE_MODE_SETTINGS' / 'ACTION_DATA_ROAMING_SETTINGS') or automate the toggle using an authorized AccessibilityService."
        ),
        PolicyRule(
            title = "4. Reading Private Notifications",
            whatAndroidForbids = "Apps cannot inspect or read notifications posted by other apps through standard APIs.",
            securityRationale = "Prevents apps from harvesting private two-factor authentication (2FA) SMS codes and personal chat messages.",
            bestWorkaround = "Register an official 'NotificationListenerService' ('android.service.notification.NotificationListenerService') and guide the user through the one-time system Settings grant flow."
        ),
        PolicyRule(
            title = "5. Inter-App UI Automation & Screen Clicking",
            whatAndroidForbids = "Apps cannot inject touch events, click buttons, or manipulate UI in other applications without special framework privileges.",
            securityRationale = "Guards against overlay tapjacking and phishing attacks.",
            bestWorkaround = "Implement an Android AccessibilityService ('AccessibilityService.performGlobalAction' and accessibility node tree traversal) with full user consent."
        ),
        PolicyRule(
            title = "6. Battery Optimization / Android Doze Mode",
            whatAndroidForbids = "When the screen turns off, Android Doze mode suspends background jobs, cuts network access, and restricts CPU wake-locks.",
            securityRationale = "Maximizes device battery longevity and prevents thermal runaway.",
            bestWorkaround = "Request exemption via 'Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' so the background wake-word audio detection loop runs uninterrupted."
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(VioletSecondary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Policy,
                            contentDescription = null,
                            tint = VioletSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "ANDROID OS RESTRICTIONS & WORKAROUNDS",
                            color = VioletSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Engineering analysis of OS limits and solutions",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        items(policies) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = item.title,
                        color = CyanPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "WHAT ANDROID FORBIDS:",
                        color = Color(0xFFEF4444),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = item.whatAndroidForbids,
                        color = TextPrimary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "OS RATIONALE: ${item.securityRationale}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurface, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "OMNIVOICE WORKAROUND:",
                                color = EmeraldTertiary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.bestWorkaround,
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
