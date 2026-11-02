package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.data.model.VoiceState
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.VioletSecondary
import com.example.ui.theme.WaveformCyan
import com.example.ui.theme.WaveformPink
import com.example.ui.theme.WaveformViolet
import kotlin.math.sin

@Composable
fun VoiceOrb(
    voiceState: VoiceState,
    audioRms: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val rmsAnim = remember { Animatable(0f) }
    LaunchedEffect(audioRms) {
        rmsAnim.animateTo(
            targetValue = (audioRms / 12f).coerceIn(0f, 1f),
            animationSpec = tween(100)
        )
    }

    val baseColors = when (voiceState) {
        is VoiceState.ListeningForCommand -> listOf(CyanPrimary, WaveformCyan, VioletSecondary)
        is VoiceState.AwaitingPassphrase, is VoiceState.VerifyingVoice -> listOf(WaveformPink, VioletSecondary, Color(0xFFF59E0B))
        is VoiceState.ConfirmingAction -> listOf(Color(0xFFF59E0B), EmeraldTertiary, CyanPrimary)
        is VoiceState.ExecutingAction -> listOf(EmeraldTertiary, CyanPrimary, VioletSecondary)
        is VoiceState.Speaking -> listOf(VioletSecondary, WaveformViolet, CyanPrimary)
        is VoiceState.RequestedUnverified -> listOf(Color(0xFFF59E0B), Color(0xFFD97706), VioletSecondary)
        is VoiceState.Error -> listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFFB91C1C))
        else -> listOf(CyanPrimary.copy(alpha = 0.5f), VioletSecondary.copy(alpha = 0.4f))
    }

    Box(
        modifier = modifier.size(180.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer glowing canvas rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2) * 0.72f * pulseScale
            val dynamicRadius = baseRadius + (rmsAnim.value * 28.dp.toPx())

            // Ambient glow halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColors[0].copy(alpha = 0.35f + (rmsAnim.value * 0.3f)),
                        baseColors[1].copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = dynamicRadius * 1.35f
                ),
                radius = dynamicRadius * 1.35f,
                center = center
            )

            // Dynamic acoustic waveform ripples
            val numPoints = 60
            for (ring in 1..2) {
                val ringRadius = dynamicRadius * (1f + (ring * 0.12f * (0.5f + rmsAnim.value)))
                val strokeWidth = (2.5f - (ring * 0.5f)).dp.toPx()

                for (i in 0 until numPoints) {
                    val angle = (i.toFloat() / numPoints) * 2 * Math.PI + Math.toRadians(rotationAngle.toDouble() * ring)
                    val waveOffset = sin(angle * 6 + rotationAngle) * (rmsAnim.value * 12.dp.toPx())
                    val r = ringRadius + waveOffset.toFloat()
                    val x = (center.x + r * kotlin.math.cos(angle)).toFloat()
                    val y = (center.y + r * kotlin.math.sin(angle)).toFloat()

                    drawCircle(
                        color = baseColors[ring % baseColors.size].copy(alpha = 0.4f / ring),
                        radius = 2.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            // Outer crisp boundary ring
            drawCircle(
                color = baseColors[0].copy(alpha = 0.8f),
                radius = dynamicRadius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

        // Core Glowing Orb
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            baseColors[0],
                            baseColors[1],
                            baseColors.getOrElse(2) { baseColors[0] }
                        )
                    )
                )
        )
    }
}
