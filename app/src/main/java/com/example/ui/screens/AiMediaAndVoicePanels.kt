package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.engine.gemini.GeminiSkillsEngine
import com.example.engine.gemini.VideoAnimationResult
import com.example.engine.gemini.VideoAspectRatio
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
// PANEL 1: CREATE & EDIT IMAGES (gemini-nano-banana-2.1)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageStudioPanel(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val generatedImages by viewModel.generatedImages.collectAsState()
    val isGenerating by viewModel.isGeneratingImage.collectAsState()

    var prompt by remember {
        mutableStateOf("Futuristic cybernetic voice assistant hologram over Lahore skyline at sunset")
    }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedBitmap = decodeUriToBitmap(context, uri)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Create & Edit Images",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Model: ${GeminiSkillsEngine.IMAGE_MODEL}",
                        color = CyanPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.testTag("pick_image_to_edit_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Pick Photo to Edit",
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (selectedBitmap == null) "Pick Photo to Edit" else "Change Photo",
                        color = CyanPrimary,
                        fontSize = 12.sp
                    )
                }
            }

            if (selectedBitmap != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = selectedBitmap!!.asImageBitmap(),
                            contentDescription = "Selected reference photo",
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Reference Photo Attached",
                                color = EmeraldTertiary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Prompt will edit & transform this image",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(onClick = { selectedBitmap = null }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove reference photo",
                            tint = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("image_prompt_input"),
                label = {
                    Text(
                        if (selectedBitmap != null) "Describe how to edit this image..."
                        else "Describe the image to create..."
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            val samplePrompts = listOf(
                "Add neon cyberpunk lighting and rain reflections",
                "Watercolor painting of Karakoram mountains under starry sky",
                "Minimalist 3D glass icon of an AI microphone"
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                samplePrompts.forEach { sample ->
                    AssistChip(
                        onClick = { prompt = sample },
                        label = { Text(sample, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = DarkSurfaceVariant,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.createOrEditImageWithGemini(prompt, selectedBitmap) },
                enabled = !isGenerating && prompt.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("generate_image_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = Color(0xFF003548)
                )
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF003548)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rendering with ${GeminiSkillsEngine.IMAGE_MODEL}...")
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedBitmap != null) "Edit Image with Gemini" else "Create Image with Gemini",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (generatedImages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                generatedImages.forEach { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Image(
                                bitmap = item.bitmap.asImageBitmap(),
                                contentDescription = item.caption,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.caption,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                AssistChip(
                                    onClick = { selectedBitmap = item.bitmap },
                                    label = { Text("Edit This", fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = CyanPrimary.copy(alpha = 0.2f),
                                        labelColor = CyanPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// PANEL 2: ANIMATE IMAGES INTO VIDEO (gemini-omni-1.1-flash via Interactions API)
// ============================================================================
@Composable
fun VideoAnimatorPanel(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val generatedVideos by viewModel.generatedVideos.collectAsState()
    val isGeneratingVideo by viewModel.isGeneratingVideo.collectAsState()

    var videoPrompt by remember {
        mutableStateOf("Smooth cinematic camera push-in with glowing atmospheric particles")
    }
    var selectedAspectRatio by remember { mutableStateOf(VideoAspectRatio.LANDSCAPE_16_9) }
    var uploadedPhoto by remember { mutableStateOf<Bitmap?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            uploadedPhoto = decodeUriToBitmap(context, uri)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Animate Images into Video",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Model: ${GeminiSkillsEngine.VIDEO_MODEL} • Interactions API",
                color = VioletSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Aspect Ratio Selector: 16:9 (landscape) or 9:16 (portrait)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Aspect Ratio:",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                VideoAspectRatio.entries.forEach { ratio ->
                    FilterChip(
                        selected = selectedAspectRatio == ratio,
                        onClick = { selectedAspectRatio = ratio },
                        label = { Text(ratio.label, fontSize = 12.sp) },
                        modifier = Modifier.testTag("aspect_ratio_${ratio.ratioString}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VioletSecondary.copy(alpha = 0.25f),
                            selectedLabelColor = VioletSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Photo Upload Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_video_source_photo_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Upload Photo",
                        tint = CyanPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (uploadedPhoto != null) "Photo Selected ✓" else "Upload Photo from Gallery",
                        color = CyanPrimary,
                        fontSize = 12.sp
                    )
                }

                if (uploadedPhoto == null) {
                    AssistChip(
                        onClick = {
                            uploadedPhoto = createSampleLandscapeBitmap()
                        },
                        label = { Text("Use Sample Photo", fontSize = 11.sp) },
                        modifier = Modifier.testTag("use_sample_photo_button"),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = DarkSurfaceVariant,
                            labelColor = EmeraldTertiary
                        )
                    )
                }
            }

            if (uploadedPhoto != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = uploadedPhoto!!.asImageBitmap(),
                    contentDescription = "Source photo to animate",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = videoPrompt,
                onValueChange = { videoPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_prompt_input"),
                label = { Text("Animation prompt (camera motion, lighting, action)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VioletSecondary,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val bmp = uploadedPhoto ?: createSampleLandscapeBitmap().also { uploadedPhoto = it }
                    viewModel.animatePhotoIntoVideo(bmp, videoPrompt, selectedAspectRatio)
                },
                enabled = !isGeneratingVideo,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("animate_video_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VioletSecondary,
                    contentColor = Color.White
                )
            ) {
                if (isGeneratingVideo) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Animating via ${GeminiSkillsEngine.VIDEO_MODEL}...")
                } else {
                    Icon(imageVector = Icons.Default.Movie, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Animate Photo into ${selectedAspectRatio.ratioString} Video",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (generatedVideos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                generatedVideos.forEach { videoItem ->
                    AnimatedVideoPreviewCard(videoItem)
                }
            }
        }
    }
}

@Composable
private fun AnimatedVideoPreviewCard(video: VideoAnimationResult) {
    var isPlaying by remember { mutableStateOf(true) }
    val infiniteTransition = rememberInfiniteTransition(label = "video_motion")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ken_burns_progress"
    )

    val ratioValue = video.aspectRatio.widthRatio / video.aspectRatio.heightRatio

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${video.modelUsed} • ${video.aspectRatio.label}",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause Video" else "Play Video",
                        tint = EmeraldTertiary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(if (video.aspectRatio == VideoAspectRatio.PORTRAIT_9_16) 0.65f else 1f)
                    .aspectRatio(ratioValue)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
            ) {
                val imgBitmap = remember(video.sourceBitmap) { video.sourceBitmap.asImageBitmap() }
                val activeProgress = if (isPlaying) progress else 0.35f

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val zoom = 1.06f + activeProgress * 0.22f
                    val panX = (activeProgress - 0.5f) * size.width * 0.08f
                    val panY = (0.5f - activeProgress) * size.height * 0.06f

                    withTransform({
                        translate(left = panX, top = panY)
                        scale(scaleX = zoom, scaleY = zoom, pivot = center)
                    }) {
                        drawImage(
                            image = imgBitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }

                    // Render glowing cinematic motion particles
                    for (i in 0 until 10) {
                        val phase = (activeProgress * 6.28f) + i * 0.7f
                        val px = (size.width * ((i * 0.11f + cos(phase) * 0.08f).coerceIn(0.05f, 0.95f)))
                        val py = (size.height * ((0.85f - activeProgress * 0.6f + sin(phase) * 0.1f).coerceIn(0.05f, 0.95f)))
                        drawCircle(
                            color = Color(video.accentHex).copy(alpha = 0.45f),
                            radius = 6f + (i % 4) * 4f,
                            center = Offset(px, py)
                        )
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (isPlaying) "● LIVE MOTION (${video.aspectRatio.ratioString})" else "PAUSED",
                        color = EmeraldTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Prompt: “${video.prompt}”",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = video.directorNotes,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

// ============================================================================
// PANEL 3: LIVE VOICE CONVERSATIONS (gemini-3.8-live) & AUDIO TRANSCRIPTION (gemini-3.5-transcribe)
// ============================================================================
@Composable
fun LiveVoiceAndTranscribePanel(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val isLiveActive by viewModel.geminiSkills.isLiveSessionActive.collectAsState()
    val liveStatus by viewModel.geminiSkills.liveStatus.collectAsState()
    val liveTurns by viewModel.geminiSkills.liveTurns.collectAsState()
    val liveRms by viewModel.geminiSkills.liveAudioRms.collectAsState()

    val isRecordingTranscribe by viewModel.geminiSkills.isRecordingTranscription.collectAsState()
    val transcribeRms by viewModel.geminiSkills.transcriptionRms.collectAsState()
    val isTranscribing by viewModel.isTranscribingAudio.collectAsState()
    val transcribedNotes by viewModel.transcribedNotes.collectAsState()

    var selectedVoice by remember { mutableStateOf("Aoede") }
    var liveTextQuickTurn by remember { mutableStateOf("") }
    var selectedLangMode by remember { mutableStateOf("Auto (English / Urdu / Roman Urdu)") }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startGeminiLiveConversation(selectedVoice)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Gemini 3.8 Live Real-Time Voice Conversation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Real-Time Voice Conversation",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = liveStatus,
                            color = if (isLiveActive) EmeraldTertiary else CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            if (isLiveActive) {
                                viewModel.stopGeminiLiveConversation()
                            } else {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    viewModel.startGeminiLiveConversation(selectedVoice)
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier.testTag("toggle_live_voice_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLiveActive) DangerRed else EmeraldTertiary,
                            contentColor = Color(0xFF002114)
                        )
                    ) {
                        Icon(
                            imageVector = if (isLiveActive) Icons.Default.Stop else Icons.Default.RecordVoiceOver,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLiveActive) "End Live" else "Start Live",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Voice Persona Chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Aoede", "Puck", "Kore", "Charon").forEach { voice ->
                        FilterChip(
                            selected = selectedVoice == voice,
                            onClick = { selectedVoice = voice },
                            label = { Text(voice, fontSize = 11.sp) }
                        )
                    }
                }

                if (isLiveActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (liveRms / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = EmeraldTertiary,
                        trackColor = DarkSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Send instant spoken turn inside Live Session
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = liveTextQuickTurn,
                        onValueChange = { liveTextQuickTurn = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_voice_text_input"),
                        placeholder = { Text("Say something or type a Live turn...", fontSize = 12.sp) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (liveTextQuickTurn.isNotBlank()) {
                                viewModel.sendGeminiLiveTextTurn(liveTextQuickTurn)
                                liveTextQuickTurn = ""
                            }
                        },
                        modifier = Modifier
                            .background(CyanPrimary, RoundedCornerShape(10.dp))
                            .testTag("send_live_turn_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Live Turn",
                            tint = Color(0xFF003548)
                        )
                    }
                }

                if (liveTurns.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    liveTurns.takeLast(5).forEach { turn ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${turn.speaker}: ",
                                color = if (turn.speaker == "You") CyanPrimary else EmeraldTertiary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = turn.text,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 2. Audio Transcription Card (gemini-3.5-transcribe)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Transcribe Microphone Audio",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Model: ${GeminiSkillsEngine.TRANSCRIBE_MODEL} • 16 kHz Mono PCM WAV",
                    color = CyanPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto (English / Urdu / Roman Urdu)", "English", "Urdu (اردو)").forEach { lang ->
                        FilterChip(
                            selected = selectedLangMode == lang,
                            onClick = { selectedLangMode = lang },
                            label = { Text(lang, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (isRecordingTranscribe) {
                    LinearProgressIndicator(
                        progress = { (transcribeRms / 100f).coerceIn(0.08f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = DangerRed,
                        trackColor = DarkSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        if (isRecordingTranscribe) {
                            viewModel.stopStudioAudioRecordingAndTranscribe(selectedLangMode)
                        } else {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                viewModel.startStudioAudioRecording()
                            } else {
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    enabled = !isTranscribing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("record_transcribe_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingTranscribe) DangerRed else CyanPrimary,
                        contentColor = Color(0xFF003548)
                    )
                ) {
                    if (isTranscribing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Transcribing with ${GeminiSkillsEngine.TRANSCRIBE_MODEL}...")
                    } else {
                        Icon(
                            imageVector = if (isRecordingTranscribe) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecordingTranscribe) "Stop & Transcribe Now" else "Record Audio to Transcribe",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (transcribedNotes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    transcribedNotes.forEach { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = note,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.speakResponse(note) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Speak Transcription",
                                        tint = CyanPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeUriToBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (_: Exception) {
        null
    }
}

private fun createSampleLandscapeBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 640f, 360f,
        intArrayOf(
            android.graphics.Color.rgb(15, 23, 42),
            android.graphics.Color.rgb(56, 189, 248),
            android.graphics.Color.rgb(129, 140, 248)
        ),
        null,
        android.graphics.Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, 640f, 360f, paint)
    paint.shader = null
    paint.color = android.graphics.Color.argb(180, 255, 255, 255)
    canvas.drawCircle(480f, 110f, 48f, paint)
    return bmp
}
