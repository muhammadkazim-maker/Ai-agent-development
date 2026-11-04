package com.example.ui.viewmodel

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.OmniVoiceDatabase
import com.example.data.local.OmniVoiceRepository
import com.example.data.local.entity.CommandHistory
import com.example.data.local.entity.FailedCommandLog
import com.example.data.local.entity.PersonalVocabulary
import com.example.data.local.entity.UserMemory
import com.example.data.model.AssistantConfig
import com.example.data.model.SecurityLogEntry
import com.example.data.model.VoiceAction
import com.example.data.model.VoiceEnrollmentProfile
import com.example.data.model.VoiceState
import com.example.data.model.VoiceVerificationResult
import com.example.data.security.SecurityManager
import com.example.engine.ActionExecutor
import com.example.engine.AiBrainEngine
import com.example.engine.AndroidBridge
import com.example.engine.AssistantAudioStateMachine
import com.example.engine.AudioPipelineOwner
import com.example.engine.AutonomousFeedEntry
import com.example.engine.AutonomousTaskEngine
import com.example.engine.BenchmarkSummary
import com.example.engine.BilingualBenchmarkRunner
import com.example.engine.DetectedLanguage
import com.example.engine.DeterministicNluEngine
import com.example.engine.ExecutionResult
import com.example.engine.GeneratedImageAsset
import com.example.engine.IntruderCaptureManager
import com.example.engine.LanguageProcessor
import com.example.engine.LearnedUserRule
import com.example.engine.LockScreenUnlockManager
import com.example.engine.MultiEngineSpeechManager
import com.example.engine.MultimodalVoiceAnalysis
import com.example.engine.NluIntentCategory
import com.example.engine.GeminiLiveVoice
import com.example.engine.LiveConnectionState
import com.example.engine.LiveSlideUpCard
import com.example.engine.MoodAndToneState
import com.example.engine.OmniAutonomousCoreEngine
import com.example.engine.OmniLiveSessionEngine
import com.example.engine.OmniOperatingMode
import com.example.engine.OmniPermanentMemory
import com.example.engine.PendingBlockedTask
import com.example.engine.PrerequisiteResolutionStep
import com.example.engine.RoutineManager
import com.example.engine.SttEngineType
import com.example.engine.TaskStepStatus
import com.example.engine.TextToSpeechManager
import com.example.engine.VoiceIntentType
import com.example.engine.VoiceRoutine
import com.example.engine.gemini.GeminiChatMessage
import com.example.engine.gemini.GeminiChatModel
import com.example.engine.gemini.GeminiSkillsEngine
import com.example.engine.gemini.ImageGenerationResult
import com.example.engine.gemini.ThinkingConfig
import com.example.engine.gemini.ThinkingLevel
import com.example.engine.gemini.VideoAnimationResult
import com.example.engine.gemini.VideoAspectRatio
import com.example.service.WakeWordForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    // Local Room Database & Repository
    val database = OmniVoiceDatabase.getDatabase(context)
    val repository = OmniVoiceRepository(
        commandHistoryDao = database.commandHistoryDao(),
        routineDao = database.routineDao(),
        personalVocabularyDao = database.personalVocabularyDao(),
        userMemoryDao = database.userMemoryDao(),
        failedCommandLogDao = database.failedCommandLogDao()
    )

    val securityManager = SecurityManager(context)
    val sttManager = MultiEngineSpeechManager(context)
    val ttsManager = TextToSpeechManager(context)
    val aiBrain = AiBrainEngine()
    val geminiSkills = GeminiSkillsEngine()
    val actionExecutor = ActionExecutor(context)
    val androidBridge = AndroidBridge.initialize(context, actionExecutor)
    val autonomousTaskEngine = AutonomousTaskEngine(context, actionExecutor, androidBridge)
    val omniLiveEngine = OmniLiveSessionEngine(context, actionExecutor, androidBridge, ttsManager)
    val routineManager = RoutineManager(context, repository)
    val unlockManager = LockScreenUnlockManager(context)
    val intruderManager = IntruderCaptureManager(context)

    // --- Official Gemini Live Voice Session Flows (`BidiGenerateContent` / `ai.live.connect`) ---
    val liveConnectionState: StateFlow<LiveConnectionState> = omniLiveEngine.connectionState
    val isLiveSessionActive: StateFlow<Boolean> = omniLiveEngine.isSessionActive
    val isLiveMicMuted: StateFlow<Boolean> = omniLiveEngine.isMicMuted
    val selectedLiveVoice: StateFlow<GeminiLiveVoice> = omniLiveEngine.selectedVoice
    val liveUserTranscript: StateFlow<String> = omniLiveEngine.liveUserTranscript
    val liveAiTranscript: StateFlow<String> = omniLiveEngine.liveAiTranscript
    val liveInputRms: StateFlow<Float> = omniLiveEngine.inputAudioRms
    val liveOutputRms: StateFlow<Float> = omniLiveEngine.outputAudioRms
    val liveFrequencyBands: StateFlow<List<Float>> = omniLiveEngine.activeFrequencyBands
    val liveSlideUpCards: StateFlow<List<LiveSlideUpCard>> = omniLiveEngine.slideUpCards
    val activeLiveModelLabel: StateFlow<String> = omniLiveEngine.activeModelLabel
    val smartContacts: StateFlow<List<com.example.engine.SmartContactEntry>> = omniLiveEngine.smartContacts
    val jarvisTerminalLogs: StateFlow<List<com.example.engine.JarvisTerminalEntry>> = com.example.engine.JarvisHandsFreeEngine.terminalLogs
    val jarvisWebhookStatus: StateFlow<String> = com.example.engine.JarvisHandsFreeEngine.webhookStatus
    val isScreenVisionActive: StateFlow<Boolean> = omniLiveEngine.isScreenVisionActive

    fun toggleScreenVision(): Boolean = omniLiveEngine.toggleScreenVision()
    fun setScreenVisionActive(active: Boolean) = omniLiveEngine.setScreenVisionActive(active)

    // --- 7-Pillar Autonomous Mobile AI OS Flows (`OmniAutonomousCoreEngine`) ---
    val operatingMode: StateFlow<OmniOperatingMode> = OmniAutonomousCoreEngine.operatingMode
    val idleSecondsRemaining: StateFlow<Int> = OmniAutonomousCoreEngine.idleSecondsRemaining
    val moodAndToneState: StateFlow<MoodAndToneState> = OmniAutonomousCoreEngine.moodAndToneState
    val permanentMemory: StateFlow<OmniPermanentMemory> = OmniAutonomousCoreEngine.permanentMemory
    val isNetworkOnline: StateFlow<Boolean> = OmniAutonomousCoreEngine.isNetworkOnline
    val isSimulatedOffline: StateFlow<Boolean> = OmniAutonomousCoreEngine.isSimulatedOffline
    val prerequisiteStep: StateFlow<PrerequisiteResolutionStep> = OmniAutonomousCoreEngine.prerequisiteStep
    val pendingBlockedTask: StateFlow<PendingBlockedTask?> = OmniAutonomousCoreEngine.pendingTaskRef

    // Contextual Conversation Memory
    private var lastTarget: String = ""
    private var lastAction: VoiceAction? = null

    // Single-Owner Microphone State Machine with strict exclusive hardware access
    val audioStateMachine = AssistantAudioStateMachine(
        onWakeListenRequested = {
            sttManager.stopListening()
            WakeWordForegroundService.resume(context)
        },
        onSttRecordingRequested = {
            WakeWordForegroundService.pause(context)
            ttsManager.stop()
            sttManager.startListening()
        },
        onThinkingRequested = {
            sttManager.stopListening()
        },
        onSpeakingRequested = {
            sttManager.stopListening()
            WakeWordForegroundService.pause(context)
        },
        onIdleRequested = {
            sttManager.stopListening()
            ttsManager.stop()
            WakeWordForegroundService.resume(context)
        }
    )

    // State flows
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _liveTranscription = MutableStateFlow("")
    val liveTranscription: StateFlow<String> = _liveTranscription.asStateFlow()

    private val _spokenResponse = MutableStateFlow("")
    val spokenResponse: StateFlow<String> = _spokenResponse.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _detectedLanguage = MutableStateFlow(DetectedLanguage.ENGLISH)
    val detectedLanguage: StateFlow<DetectedLanguage> = _detectedLanguage.asStateFlow()

    private val _preferredLanguageMode = MutableStateFlow("Auto (Urdu / Roman Urdu / English)")
    val preferredLanguageMode: StateFlow<String> = _preferredLanguageMode.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _config = MutableStateFlow(AssistantConfig())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<SecurityLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<SecurityLogEntry>> = _auditLogs.asStateFlow()

    private val _lastExecutedAction = MutableStateFlow<VoiceAction?>(null)
    val lastExecutedAction: StateFlow<VoiceAction?> = _lastExecutedAction.asStateFlow()

    private val _plannedSteps = MutableStateFlow<List<String>>(emptyList())
    val plannedSteps: StateFlow<List<String>> = _plannedSteps.asStateFlow()

    private val _personalVocabulary = MutableStateFlow<List<PersonalVocabulary>>(emptyList())
    val personalVocabulary: StateFlow<List<PersonalVocabulary>> = _personalVocabulary.asStateFlow()

    private val _userMemories = MutableStateFlow<List<UserMemory>>(emptyList())
    val userMemories: StateFlow<List<UserMemory>> = _userMemories.asStateFlow()

    private val _failedLogs = MutableStateFlow<List<FailedCommandLog>>(emptyList())
    val failedLogs: StateFlow<List<FailedCommandLog>> = _failedLogs.asStateFlow()

    private val _benchmarkSummary = MutableStateFlow<BenchmarkSummary?>(null)
    val benchmarkSummary: StateFlow<BenchmarkSummary?> = _benchmarkSummary.asStateFlow()

    val activeSttEngine: StateFlow<SttEngineType> = sttManager.activeEngine
    val sttLastError: StateFlow<String?> = sttManager.lastError
    val audioFrequencyBands: StateFlow<List<Float>> = sttManager.audioFrequencyBands
    val audioLevelDb: StateFlow<Float> = sttManager.audioLevelDb
    val recordingDurationMs: StateFlow<Long> = sttManager.recordingDurationMs
    val lastVoiceAnalysis: StateFlow<MultimodalVoiceAnalysis?> = sttManager.lastVoiceAnalysis
    val needsMicActivation: StateFlow<Boolean> = sttManager.needsMicActivation
    val lastRecordedWavBytes: StateFlow<ByteArray?> = sttManager.lastRecordedWavBytes
    val lastRecordedDurationSec: StateFlow<Float> = sttManager.lastRecordedDurationSec
    val lastRecordedSizeKb: StateFlow<Int> = sttManager.lastRecordedSizeKb
    val lastRecordedPeakVolumePercent: StateFlow<Int> = sttManager.lastRecordedPeakVolumePercent
    val isPlayingRecordedAudio: StateFlow<Boolean> = sttManager.isPlayingRecordedAudio

    fun togglePlayLastRecordedWav() {
        sttManager.togglePlayLastRecordedWav()
    }

    private val _autonomousFeed = MutableStateFlow<List<AutonomousFeedEntry>>(emptyList())
    val autonomousFeed: StateFlow<List<AutonomousFeedEntry>> = _autonomousFeed.asStateFlow()

    // Default to Tap-to-Start / Tap-to-Send (false) with optional 2.5s Auto-Silence toggle
    private val _isMicLockedAlwaysListening = MutableStateFlow(false)
    val isMicLockedAlwaysListening: StateFlow<Boolean> = _isMicLockedAlwaysListening.asStateFlow()

    private val _isHandsFreeAlwaysOn = MutableStateFlow(false)
    val isHandsFreeAlwaysOn: StateFlow<Boolean> = _isHandsFreeAlwaysOn.asStateFlow()

    private val _isLockedModeSimulated = MutableStateFlow(false)
    val isLockedModeSimulated: StateFlow<Boolean> = _isLockedModeSimulated.asStateFlow()

    private val _pendingAction = MutableStateFlow<VoiceAction?>(null)
    val pendingAction: StateFlow<VoiceAction?> = _pendingAction.asStateFlow()

    private val _pendingQuery = MutableStateFlow("")

    private val _enrolledProfile = MutableStateFlow(VoiceEnrollmentProfile())
    val enrolledProfile: StateFlow<VoiceEnrollmentProfile> = _enrolledProfile.asStateFlow()

    private val _hasPassphrase = MutableStateFlow(false)
    val hasPassphrase: StateFlow<Boolean> = _hasPassphrase.asStateFlow()

    private val _lockoutSeconds = MutableStateFlow(0L)
    val lockoutSeconds: StateFlow<Long> = _lockoutSeconds.asStateFlow()

    private val _storedPin = MutableStateFlow("")
    val storedPin: StateFlow<String> = _storedPin.asStateFlow()

    private val _learnedRules = MutableStateFlow<List<LearnedUserRule>>(emptyList())
    val learnedRules: StateFlow<List<LearnedUserRule>> = _learnedRules.asStateFlow()

    val isServiceRunning: StateFlow<Boolean> = WakeWordForegroundService.isServiceActive
    val wakeSpeechLifecycleState: StateFlow<com.example.engine.WakeSpeechLifecycleState> =
        AssistantAudioStateMachine.shared.lifecycleState
    val activeMicOwner: StateFlow<com.example.engine.MicComponentOwner> =
        AssistantAudioStateMachine.shared.activeMicOwner
    val activeAudioInputRoute: StateFlow<com.example.engine.AudioInputRouteType> =
        AssistantAudioStateMachine.shared.activeInputRoute
    val isUserArmed: StateFlow<Boolean> = AssistantAudioStateMachine.shared.isUserArmedFlow
    val isStoppedByUser: StateFlow<Boolean> = AssistantAudioStateMachine.shared.isStoppedByUserFlow
    val isDeviceMuted: StateFlow<Boolean> = AssistantAudioStateMachine.shared.isDeviceMutedFlow
    val isCommandWindowOpen: StateFlow<Boolean> = AssistantAudioStateMachine.shared.isCommandWindowOpenFlow

    private var lockoutCountdownJob: Job? = null

    init {
        com.omnivoice.agentfix.AgentBridge.appContext = context.applicationContext
        AssistantAudioStateMachine.shared.inspectWakeAndAssistantReadiness(context)
        setupSttCallbacks()

        viewModelScope.launch(Dispatchers.IO) {
            LanguageProcessor.initializeWithContext(context)
            OmniAutonomousCoreEngine.initialize(context)
            _enrolledProfile.value = securityManager.getEnrolledVoiceProfile()
            _hasPassphrase.value = securityManager.hasPassphrase()
            _learnedRules.value = DeterministicNluEngine.getLearnedRules(context)
            checkLockoutTimer()
        }

        // Observe STT audio level
        viewModelScope.launch {
            sttManager.audioLevelRms.collect { level ->
                _audioRms.value = level
            }
        }

        // Observe STT partial text
        viewModelScope.launch {
            sttManager.partialText.collect { partial ->
                _liveTranscription.value = partial
            }
        }

        // Observe WakeWord background service events
        viewModelScope.launch {
            WakeWordForegroundService.wakeWordTriggered.collect { wakeWord ->
                onWakeWordTriggered(wakeWord)
            }
        }

        // Observe Room command history and sync to audit logs
        viewModelScope.launch {
            repository.allHistory.collect { historyList ->
                _auditLogs.value = historyList.map { h ->
                    SecurityLogEntry(
                        id = h.id,
                        timestamp = h.timestamp,
                        commandText = h.rawSpeech,
                        actionType = h.actionType,
                        isLockedState = h.isLockedMode,
                        verificationPassed = h.verificationPassed,
                        responseSummary = h.responseSummary
                    )
                }
            }
        }

        // Observe Personal Vocabulary from Room
        viewModelScope.launch {
            repository.allPersonalVocabulary.collect { vocabList ->
                _personalVocabulary.value = vocabList
                vocabList.filter { it.category == "contact_nickname" }.forEach {
                    LanguageProcessor.registerCustomNickname(it.spokenPhrase, it.canonicalMeaning)
                }
            }
        }

        // Observe Long-Term User Memory from Room
        viewModelScope.launch {
            repository.allUserMemories.collect { memories ->
                _userMemories.value = memories
            }
        }

        // Observe Self-Learning Failed Command Log from Room
        viewModelScope.launch {
            repository.allFailedLogs.collect { logs ->
                _failedLogs.value = logs
            }
        }
    }

    private fun setupSttCallbacks() {
        sttManager.onShortOrSilentAudioCallback = { warningMessage ->
            audioStateMachine.transitionTo(AudioPipelineOwner.IDLE)
            _liveTranscription.value = warningMessage
            _spokenResponse.value = warningMessage
            _voiceState.value = VoiceState.Idle
        }

        sttManager.onResultCallback = { result ->
            audioStateMachine.transitionTo(AudioPipelineOwner.THINKING)
            handleSpeechResult(result, sttManager.lastVoiceAnalysis.value)
        }

        sttManager.onErrorCallback = { errorCode, message ->
            Log.d("VoiceAssistantViewModel", "Audio capture status: $message ($errorCode)")
            audioStateMachine.transitionTo(AudioPipelineOwner.IDLE)
            _voiceState.value = VoiceState.Idle
        }
    }

    fun toggleDarkTheme(dark: Boolean) {
        _isDarkTheme.value = dark
    }

    fun addLearnedRule(ruleText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _learnedRules.value = DeterministicNluEngine.saveLearnedRule(context, ruleText)
            repository.saveUserMemory(
                key = "learned_rule:${System.currentTimeMillis()}",
                value = ruleText.trim(),
                type = "learned_rule"
            )
        }
    }

    fun editLearnedRule(ruleId: Long, updatedText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _learnedRules.value = DeterministicNluEngine.updateLearnedRule(context, ruleId, updatedText)
        }
    }

    fun deleteLearnedRule(ruleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _learnedRules.value = DeterministicNluEngine.deleteLearnedRule(context, ruleId)
        }
    }

    fun setPreferredLanguageMode(mode: String) {
        _preferredLanguageMode.value = mode
    }

    fun isDeviceLocked(): Boolean {
        if (_isLockedModeSimulated.value) return true
        return unlockManager.isDeviceLocked()
    }

    fun toggleSimulatedLock(isLocked: Boolean) {
        _isLockedModeSimulated.value = isLocked
    }

    fun toggleSimulateLockedMode() {
        _isLockedModeSimulated.value = !_isLockedModeSimulated.value
    }

    fun onWakeWordTriggered(wakeWord: String) {
        Log.i("VoiceAssistantViewModel", "Wake-word triggered in ViewModel: $wakeWord")
        val coord = AssistantAudioStateMachine.shared
        if (coord.isSelfTriggerSuppressed() || coord.isStoppedByUserInMemory() || coord.isDeviceMutedInMemory()) {
            return
        }
        val inlineCmd = if (wakeWord.contains(":")) {
            wakeWord.substringAfter(":").trim()
        } else {
            AssistantAudioStateMachine.extractCommandAfterWakePhrase(wakeWord)
        }
        if (inlineCmd.isNotBlank()) {
            omniLiveEngine.sendTextMessageToLiveSession(inlineCmd)
            return
        }
        if (operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY) {
            wakeUpAssistantFromStandby()
            return
        }
        coord.openCommandWindow(reason = "WAKE_WORD_TRIGGERED_JARVIS")
        if (isLiveSessionActive.value) {
            OmniAutonomousCoreEngine.resetInactivityTimer()
            return
        }
        startListeningForCommand()
    }

    fun startListeningForCommand() {
        val coord = AssistantAudioStateMachine.shared
        coord.armUserSessionAndPersist(context, "USER_START_LISTENING_FOR_COMMAND")
        coord.openCommandWindow(reason = "EXPLICIT_USER_COMMAND_WINDOW")
        // Single-owner microphone rule: never start legacy STT recording while Gemini Live WebSocket owns the mic
        if (isLiveSessionActive.value) {
            sttManager.stopListening()
            OmniAutonomousCoreEngine.resetInactivityTimer()
            return
        }
        // Prevent duplicate listening session if already listening
        if (_voiceState.value is VoiceState.ListeningForCommand ||
            audioStateMachine.currentOwner.value == AudioPipelineOwner.STT_RECORDING
        ) {
            return
        }
        _liveTranscription.value = ""
        _voiceState.value = VoiceState.ListeningForCommand()
        coord.acquireMicrophoneOwnership(com.example.engine.MicComponentOwner.COMMAND_WINDOW_CAPTURE) {
            sttManager.stopListening()
        }
        audioStateMachine.transitionTo(AudioPipelineOwner.STT_RECORDING)
    }

    fun stopAndFinalizeSpeech() {
        sttManager.stopAndFinalizeSpeech()
    }

    fun stopListening() {
        _isMicLockedAlwaysListening.value = false
        sttManager.setContinuousLockMode(false)
        audioStateMachine.transitionTo(AudioPipelineOwner.IDLE)
        if (_voiceState.value is VoiceState.ListeningForCommand || _voiceState.value is VoiceState.AwaitingPassphrase) {
            _voiceState.value = VoiceState.Idle
        }
    }

    fun toggleMicLockAlwaysListening() {
        val next = !_isMicLockedAlwaysListening.value
        _isMicLockedAlwaysListening.value = next
        sttManager.setContinuousLockMode(next)
        if (next) {
            startListeningForCommand()
        } else {
            stopListening()
        }
    }

    fun setHandsFreeAlwaysOn(enabled: Boolean) {
        _isHandsFreeAlwaysOn.value = enabled
        _isMicLockedAlwaysListening.value = enabled
        sttManager.setContinuousLockMode(enabled)
        if (enabled) {
            WakeWordForegroundService.start(context)
            startListeningForCommand()
        } else {
            WakeWordForegroundService.stop(context)
            stopListening()
        }
    }

    fun cancelAutonomousAction(entryId: Long) {
        _autonomousFeed.value = _autonomousFeed.value.map { entry ->
            if (entry.id == entryId) {
                entry.copy(
                    isExecuting = false,
                    isCancelled = true,
                    steps = entry.steps.map { step ->
                        if (step.status == TaskStepStatus.IN_PROGRESS || step.status == TaskStepStatus.PENDING) {
                            step.copy(status = TaskStepStatus.FAILED, detail = "Cancelled by user")
                        } else {
                            step
                        }
                    }
                )
            } else {
                entry
            }
        }
        undoLastAction()
        val msg = LanguageProcessor.formatReplyInLanguage(
            "Action cancelled.",
            "Action cancel kar diya gaya hai.",
            "ایکشن منسوخ کر دیا گیا ہے۔",
            _detectedLanguage.value
        )
        _voiceState.value = VoiceState.Idle
        speakResponse(msg)
    }

    fun applyGeneratedImageToWhatsAppDp(asset: GeneratedImageAsset) {
        viewModelScope.launch {
            AndroidBridge.emitToolAction(
                tool = "update_whatsapp_dp",
                params = mapOf("imageSource" to asset.fileUri, "prompt" to asset.prompt, "imageUrl" to asset.imageUrl),
                bridge = androidBridge
            )
            val msg = LanguageProcessor.formatReplyInLanguage(
                "Applying '${asset.prompt}' to your WhatsApp DP now.",
                "'${asset.prompt}' ki image aap ki WhatsApp DP par apply kar di gayi hai.",
                "'${asset.prompt}' کی تصویر آپ کی واٹس ایپ ڈی پی پر لگا دی گئی ہے۔",
                _detectedLanguage.value
            )
            speakResponse(msg)
        }
    }

    private fun handleSpeechResult(rawSpeech: String, analysis: MultimodalVoiceAnalysis? = null) {
        val currentState = _voiceState.value
        _liveTranscription.value = rawSpeech
        if (rawSpeech.isNotBlank()) {
            com.omnivoice.agentfix.AgentSession.onUserUtterance(rawSpeech)
        }

        when (currentState) {
            is VoiceState.AwaitingPassphrase, is VoiceState.VerifyingVoice -> {
                verifySpokenPassphrase(rawSpeech)
            }
            is VoiceState.ConfirmingAction -> {
                handleConfirmationResponse(rawSpeech, currentState.action)
            }
            else -> {
                processUserCommand(rawSpeech, analysis)
            }
        }
    }

    fun submitTextCommand(commandText: String) {
        _liveTranscription.value = commandText
        if (commandText.isNotBlank()) {
            com.omnivoice.agentfix.AgentSession.onUserUtterance(commandText)
        }
        processUserCommand(commandText, null)
    }

    fun executeDiagnosticTypedCommand(
        commandText: String,
        visibleHomeMode: Boolean = true,
        coldStartTimeoutMs: Long = com.omnivoice.agentfix.UiTools.defaultColdStartTimeoutMs
    ): com.omnivoice.agentfix.CanonicalAppLaunchResult {
        _liveTranscription.value = commandText
        if (commandText.isNotBlank()) {
            com.omnivoice.agentfix.AgentSession.onUserUtterance(commandText)
        }
        val canonical = actionExecutor.executeDiagnosticTypedCommand(
            commandText = commandText,
            visibleHomeMode = visibleHomeMode,
            coldStartTimeoutMs = coldStartTimeoutMs
        )
        _spokenResponse.value = canonical.message
        _voiceState.value = when (canonical.status) {
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.VERIFIED -> VoiceState.Idle
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED ->
                VoiceState.RequestedUnverified(canonical.message, canonical.reasonCode)
            else -> VoiceState.Error(canonical.message)
        }
        return canonical
    }

    private fun processUserCommand(speech: String, voiceAnalysis: MultimodalVoiceAnalysis? = null) {
        _voiceState.value = VoiceState.ProcessingAi(speech)
        _pendingQuery.value = speech

        val contextualSpeech = resolveContextualReferences(speech)

        viewModelScope.launch {
            // STRICT TWO-STEP VERIFICATION:
            // Only bypass device actions if the audio was literally unclear or a pure mic-check/greeting
            // AND SuperAgiBrainEngine does not detect an explicit device task in the utterance.
            val hasExplicitDeviceTask = com.example.engine.SuperAgiBrainEngine.extractAllDeviceTasks(context, contextualSpeech).isNotEmpty()
            val isExplicitConversationOrUnclear = !hasExplicitDeviceTask && (
                voiceAnalysis?.intentType == VoiceIntentType.UNCLEAR ||
                speech.equals("[Unclear Audio]", ignoreCase = true) ||
                MultiEngineSpeechManager.isConversationalQuery(contextualSpeech)
            )

            if (!isExplicitConversationOrUnclear && !isDeviceLocked()) {
                // 1. Check for matching custom routines first (backed by Room database) only when unlocked
                val routine = routineManager.findMatchingRoutineFromDb(contextualSpeech)
                if (routine != null) {
                    executeRoutine(routine)
                    return@launch
                }
            }

            // 2. Check Personal Vocabulary custom command mappings ("batti jalao" -> "turn on flashlight")
            val matchedVocab = if (!isExplicitConversationOrUnclear) {
                _personalVocabulary.value.firstOrNull {
                    contextualSpeech.contains(it.spokenPhrase, ignoreCase = true) && it.category == "custom_command"
                }
            } else null

            val effectiveSpeech = if (matchedVocab != null) {
                contextualSpeech.replace(matchedVocab.spokenPhrase, matchedVocab.canonicalMeaning, ignoreCase = true)
            } else {
                contextualSpeech
            }

            // 3. Process through AI Brain (fast-path resolves explicit OS/video/search actions in <2ms!)
            val recentContext = "Last target: $lastTarget, Last action: ${lastAction?.action ?: "none"}"
            val vocabSummary = _personalVocabulary.value.take(10).joinToString("; ") { "${it.spokenPhrase}=${it.canonicalMeaning}" }
            val memorySummary = _userMemories.value.take(10).joinToString("; ") { "${it.memoryKey}:${it.memoryValue}" }

            val (action, lang) = if (isExplicitConversationOrUnclear && !voiceAnalysis?.reply.isNullOrBlank()) {
                val detected = LanguageProcessor.detectLanguage(effectiveSpeech)
                VoiceAction(
                    action = "general_chat",
                    target = effectiveSpeech,
                    spokenResponse = voiceAnalysis?.reply.orEmpty(),
                    confirmationRequired = false
                ) to detected
            } else {
                aiBrain.processCommand(
                    userSpeech = effectiveSpeech,
                    recentContext = recentContext,
                    personalVocabContext = vocabSummary,
                    longTermMemoryContext = memorySummary
                )
            }
            _detectedLanguage.value = lang
            _pendingAction.value = action

            val lowerSpeech = effectiveSpeech.lowercase()
            val needsAutonomousMultiStepOrImage =
                action.action == "multi_step" ||
                    lowerSpeech.contains("image") ||
                    lowerSpeech.contains("tasveer") ||
                    lowerSpeech.contains("picture") ||
                    lowerSpeech.contains("dp ") ||
                    lowerSpeech.contains("profile photo") ||
                    (voiceAnalysis?.deterministicTasks?.size ?: 0) > 1

            if (isDeviceLocked() && LockScreenUnlockManager.isBlockedWhenDeviceLocked(action.action)) {
                val blockedMsg = LanguageProcessor.formatReplyInLanguage(
                    "Blocked: '${action.action}' cannot run while your phone is locked. Please unlock your phone using Android's standard lock screen.",
                    "Blocked: Phone lock hone ki wajah se '${action.action}' nahi chal sakta. Pehle Android lock screen se phone unlock karein.",
                    "بلاک: فون لاک ہونے کی وجہ سے یہ ایکشن نہیں چل سکتا۔ پہلے فون کو ان لاک کریں۔",
                    lang
                )
                _voiceState.value = VoiceState.Error("Blocked While Locked", blockedMsg)
                speakResponse(blockedMsg)
                recordLog(action, isLocked = true, passed = false, summary = blockedMsg)
                return@launch
            }

            if (needsAutonomousMultiStepOrImage) {
                val wavBytes = if (voiceAnalysis != null) null else sttManager.lastRecordedWavBytes.value
                val completedFeedEntry = autonomousTaskEngine.executeAutonomousCommand(
                    rawCommand = effectiveSpeech,
                    audioWavBytes = wavBytes,
                    preVerifiedIntent = voiceAnalysis?.intentType,
                    preVerifiedReply = voiceAnalysis?.reply,
                    preVerifiedReasoning = voiceAnalysis?.chainOfThoughtReasoning,
                    preVerifiedConfidence = voiceAnalysis?.confidenceScore,
                    preVerifiedClarity = voiceAnalysis?.audioClarityScore,
                    preVerifiedCategory = voiceAnalysis?.intentCategory,
                    onProgressUpdate = { liveEntry ->
                        val existingIdx = _autonomousFeed.value.indexOfFirst { it.id == liveEntry.id }
                        if (existingIdx >= 0) {
                            val updated = _autonomousFeed.value.toMutableList()
                            updated[existingIdx] = liveEntry
                            _autonomousFeed.value = updated
                        } else {
                            _autonomousFeed.value = (listOf(liveEntry) + _autonomousFeed.value).take(25)
                        }
                        _plannedSteps.value = liveEntry.steps.map { s ->
                            val badge = when (s.status) {
                                TaskStepStatus.COMPLETED -> "✓"
                                TaskStepStatus.IN_PROGRESS -> "⟳"
                                TaskStepStatus.FAILED -> "✕"
                                TaskStepStatus.PENDING -> "•"
                            }
                            "${s.stepIndex}. ${s.title} $badge"
                        }
                    }
                )

                if (completedFeedEntry.intentCategory == NluIntentCategory.LEARN_CORRECTION_RULE) {
                    _learnedRules.value = DeterministicNluEngine.getLearnedRules(context)
                }

                if (completedFeedEntry.intentType == VoiceIntentType.CONVERSATION ||
                    completedFeedEntry.intentType == VoiceIntentType.UNCLEAR ||
                    action.action == "general_chat" ||
                    completedFeedEntry.generatedImage != null ||
                    completedFeedEntry.steps.size > 1
                ) {
                    val replyText = completedFeedEntry.aiReply
                    speakResponse(replyText)
                    _voiceState.value = VoiceState.Idle
                    recordLog(
                        action = if (completedFeedEntry.intentType != VoiceIntentType.ACTION) {
                            VoiceAction(action = "conversation", target = effectiveSpeech, spokenResponse = replyText)
                        } else action,
                        isLocked = isDeviceLocked(),
                        passed = true,
                        summary = replyText
                    )
                    return@launch
                }
            } else if (action.action == "general_chat") {
                val replyText = action.spokenResponse.ifBlank {
                    com.example.engine.SuperAgiBrainEngine.generateLocalSuperAgiResponse(
                        context = context,
                        rawText = effectiveSpeech,
                        detectedLang = if (lang == DetectedLanguage.ENGLISH) com.example.engine.NluLanguageEnum.ENGLISH else com.example.engine.NluLanguageEnum.ROMAN_URDU_MIXED
                    )
                }
                speakResponse(replyText)
                _voiceState.value = VoiceState.Idle
                recordLog(
                    action = VoiceAction(action = "conversation", target = effectiveSpeech, spokenResponse = replyText),
                    isLocked = isDeviceLocked(),
                    passed = true,
                    summary = replyText
                )
                return@launch
            }

            // Handle "teach_word" or "save_conditional_rule" persistence in Room
            if (action.action == "teach_word") {
                val phrase = action.parameters["phrase"] ?: action.target
                val meaning = action.parameters["meaning"] ?: "custom command"
                repository.teachPersonalWord(phrase, meaning)
            } else if (action.action == "save_conditional_rule") {
                val trigger = action.parameters["trigger"] ?: action.target
                val steps = action.parameters["steps"] ?: ""
                repository.saveUserMemory("rule:$trigger", steps, "conditional_rule")
            }

            // If general_chat fallback was triggered on an unrecognized command, log to Self-Learning Failed Command Log
            if (action.action == "general_chat" && action.target != "time" && action.target != "date" && speech.length > 4) {
                repository.logFailedCommand(
                    rawUtterance = speech,
                    normalizedUtterance = LanguageProcessor.normalizeInput(speech),
                    detectedLanguage = lang.label,
                    failureReason = "Conversational fallback (unmapped tool intent)"
                )
            }

            if (action.target.isNotBlank()) {
                lastTarget = action.target
            }
            lastAction = action

            val locked = isDeviceLocked()

            if (locked) {
                // LOCKED MODE
                if (!securityManager.hasPassphrase()) {
                    val prompt = LanguageProcessor.formatReplyInLanguage(
                        "Device is locked. Passphrase not set. Please unlock device to configure security.",
                        "Device locked hai. Pehle Security tab mein jakar passphrase set karein.",
                        "ڈیوائس لاک ہے۔ پہلے سیکیورٹی ٹیب میں پاس فریز سیٹ کریں۔",
                        lang
                    )
                    speakResponse(prompt)
                    _voiceState.value = VoiceState.Error("Passphrase not set up", "Open Security tab to set passphrase.")
                    return@launch
                }

                if (securityManager.isLockedOut()) {
                    val remaining = securityManager.getRemainingLockoutSeconds()
                    val prompt = LanguageProcessor.formatReplyInLanguage(
                        "Security lockout active. Please wait $remaining seconds or unlock device with biometric PIN.",
                        "Security lockout lag gaya hai. $remaining seconds intazar karein.",
                        "سیکیورٹی لاک آؤٹ فعال ہے۔ $remaining سیکنڈ انتظار کریں۔",
                        lang
                    )
                    speakResponse(prompt)
                    _voiceState.value = VoiceState.Error("Locked Out", "$remaining seconds remaining.")
                    startLockoutCountdown()
                    return@launch
                }

                val prompt = LanguageProcessor.formatReplyInLanguage(
                    "Device is locked. Please speak your security passphrase.",
                    "Phone lock hai. Baraye meherbani apna secret passphrase bolein.",
                    "فون لاک ہے۔ براہِ کرم اپنا خفیہ پاس فریز بولیں۔",
                    lang
                )
                _voiceState.value = VoiceState.AwaitingPassphrase(prompt)
                speakResponse(prompt) {
                    viewModelScope.launch {
                        delay(400)
                        audioStateMachine.transitionTo(AudioPipelineOwner.STT_RECORDING)
                    }
                }
            } else {
                // UNLOCKED MODE
                if (action.confirmationRequired) {
                    _voiceState.value = VoiceState.ConfirmingAction(action, action.spokenResponse)
                    speakResponse(action.spokenResponse) {
                        viewModelScope.launch {
                            delay(400)
                            audioStateMachine.transitionTo(AudioPipelineOwner.STT_RECORDING)
                        }
                    }
                } else {
                    executeAndCompleteAction(action, isLockedState = false)
                }
            }
        }
    }

    private fun resolveContextualReferences(speech: String): String {
        val lower = speech.lowercase().trim()
        if (lastTarget.isNotBlank()) {
            if (lower.contains("call him") || lower.contains("call her") || lower.contains("call them") ||
                lower.contains("usse call karo") || lower.contains("usko call") || speech.contains("اسے کال کرو")
            ) {
                return "call $lastTarget"
            }
            if (lower.contains("text him") || lower.contains("text her") || lower.contains("usko message") ||
                lower.contains("usse text") || speech.contains("اسے میسج بھیجو")
            ) {
                return "text $lastTarget"
            }
        }
        if ((lower.contains("same as yesterday") || lower.contains("kal wala")) && lastAction != null) {
            return "${lastAction?.action} ${lastAction?.target}"
        }
        return speech
    }

    private fun verifySpokenPassphrase(spokenPassphrase: String) {
        _voiceState.value = VoiceState.VerifyingVoice(spokenPassphrase)
        val action = _pendingAction.value ?: return

        val evalResult = securityManager.evaluateLockedMode(
            spokenPassphrase = spokenPassphrase,
            audioSamples = null,
            config = _config.value,
            actionType = action.action
        )

        when (evalResult) {
            is VoiceVerificationResult.Success -> {
                viewModelScope.launch {
                    unlockManager.attemptUnlock(null)
                    if (isDeviceLocked() && LockScreenUnlockManager.isBlockedWhenDeviceLocked(action.action)) {
                        val lockedMsg = LanguageProcessor.formatReplyInLanguage(
                            "Identity verified, but your phone is still locked. Private actions are blocked until you unlock Android's standard lock screen.",
                            "Pehchan tasdeeq ho gayi, lekin phone abhi bhi lock hai. Private actions ke liye pehle phone unlock karein.",
                            "شناخت کی تصدیق ہو گئی لیکن فون ابھی بھی لاک ہے۔ پرائیویٹ ایکشنز کے لیے پہلے فون ان لاک کریں۔",
                            _detectedLanguage.value
                        )
                        _voiceState.value = VoiceState.Error("Blocked While Locked", lockedMsg)
                        speakResponse(lockedMsg)
                        recordLog(action, isLocked = true, passed = false, summary = lockedMsg)
                    } else if (action.confirmationRequired) {
                        _voiceState.value = VoiceState.ConfirmingAction(action, action.spokenResponse)
                        speakResponse(action.spokenResponse) {
                            viewModelScope.launch {
                                delay(400)
                                audioStateMachine.transitionTo(AudioPipelineOwner.STT_RECORDING)
                            }
                        }
                    } else {
                        executeAndCompleteAction(action, isLockedState = isDeviceLocked())
                    }
                }
            }
            is VoiceVerificationResult.PassphraseFailed -> {
                val attempts = evalResult.remainingAttempts
                val failMsg = LanguageProcessor.formatReplyInLanguage(
                    "Passphrase incorrect. $attempts attempts remaining.",
                    "Passphrase ghalat hai. $attempts koshishein baqi hain.",
                    "پاس فریز غلط ہے۔ $attempts کوششیں باقی ہیں۔",
                    _detectedLanguage.value
                )
                _voiceState.value = VoiceState.Error("Incorrect Passphrase", "$attempts attempts remaining.")
                speakResponse(failMsg)
                intruderManager.triggerIntruderAlert("Failed Passphrase Attempt")
                checkLockoutTimer()
                recordLog(action, isLocked = true, passed = false, summary = failMsg)
            }
            is VoiceVerificationResult.BiometricsFailed -> {
                val bioMsg = LanguageProcessor.formatReplyInLanguage(
                    "Voice mismatch detected.",
                    "Awaz match nahi hui. Sirf owner ki awaz chal sakti hai.",
                    "آواز میچ نہیں ہوئی۔ صرف مالک کی آواز قبول ہو گی۔",
                    _detectedLanguage.value
                )
                _voiceState.value = VoiceState.Error("Voice Biometric Mismatch", "Only registered voice accepted.")
                speakResponse(bioMsg)
                intruderManager.triggerIntruderAlert("Voice Biometrics Failed")
                checkLockoutTimer()
                recordLog(action, isLocked = true, passed = false, summary = bioMsg)
            }
            is VoiceVerificationResult.LockedOut -> {
                val lockMsg = LanguageProcessor.formatReplyInLanguage(
                    "Maximum attempts exceeded. Device locked for 5 minutes.",
                    "Hadd khatam ho gayi. Phone 5 minute ke liye lock ho gaya.",
                    "حد ختم ہو گئی۔ فون 5 منٹ کے لیے لاک ہو گیا۔",
                    _detectedLanguage.value
                )
                _voiceState.value = VoiceState.Error("Locked Out", "Too many failed attempts.")
                speakResponse(lockMsg)
                intruderManager.triggerIntruderAlert("Lockout Activated")
                startLockoutCountdown()
                recordLog(action, isLocked = true, passed = false, summary = lockMsg)
            }
            is VoiceVerificationResult.SensitiveActionBlocked -> {
                val sensMsg = LanguageProcessor.formatReplyInLanguage(
                    "Sensitive action blocked while locked.",
                    "Khatarnaak action locked halat mein manaa hai.",
                    "حساس ایکشن لاک حالت میں ممنوع ہے۔",
                    _detectedLanguage.value
                )
                _voiceState.value = VoiceState.Error("Blocked Action", "Action restricted while phone is locked.")
                speakResponse(sensMsg)
                recordLog(action, isLocked = true, passed = false, summary = sensMsg)
            }
        }
    }

    fun confirmPendingActionNow() {
        val action = _pendingAction.value ?: return
        executeAndCompleteAction(action, isLockedState = isDeviceLocked())
    }

    fun cancelPendingActionNow() {
        val action = _pendingAction.value ?: return
        val msg = LanguageProcessor.formatReplyInLanguage(
            "Action cancelled.",
            "Action cancel kar diya gaya.",
            "ایکشن منسوخ کر دیا گیا۔",
            _detectedLanguage.value
        )
        _voiceState.value = VoiceState.Idle
        speakResponse(msg)
        recordLog(action, isLocked = isDeviceLocked(), passed = true, summary = msg)
    }

    private fun handleConfirmationResponse(responseSpeech: String, action: VoiceAction) {
        val lower = responseSpeech.lowercase()
        val isConfirmed = lower.contains("yes") || lower.contains("sure") || lower.contains("confirm") ||
                lower.contains("do it") || lower.contains("yeah") || lower.contains("haan") ||
                lower.contains("han") || lower.contains("ji") || lower.contains("kardo") ||
                lower.contains("theek hai") || responseSpeech.contains("ہاں") || responseSpeech.contains("جی")
        val isCancelled = lower.contains("no") || lower.contains("cancel") || lower.contains("stop") ||
                lower.contains("abort") || lower.contains("don't") || lower.contains("nahi") ||
                lower.contains("nahin") || responseSpeech.contains("مت کرو") || responseSpeech.contains("نہیں")

        if (isConfirmed) {
            executeAndCompleteAction(action, isLockedState = isDeviceLocked())
        } else if (isCancelled) {
            cancelPendingActionNow()
        } else {
            val msg = LanguageProcessor.formatReplyInLanguage(
                "Please say Yes to confirm or No to cancel.",
                "Tasdeeq ke liye Haan bolein ya Cancel bolein.",
                "تصدیق کے لیے ہاں یا کینسل بولیں۔",
                _detectedLanguage.value
            )
            speakResponse(msg) {
                viewModelScope.launch {
                    delay(400)
                    audioStateMachine.transitionTo(AudioPipelineOwner.STT_RECORDING)
                }
            }
        }
    }

    private fun executeRoutine(routine: VoiceRoutine) {
        routineManager.recordRoutineExecution(routine.id)
        _plannedSteps.value = routine.actions.mapIndexed { idx, act ->
            "Step ${idx + 1}: ${act.action}(${act.target.ifBlank { "default" }})"
        }
        speakResponse(routine.confirmationMessage) {
            viewModelScope.launch {
                for (action in routine.actions) {
                    actionExecutor.execute(action)
                    delay(300)
                }
                _voiceState.value = VoiceState.Idle
                recordLog(
                    VoiceAction(action = "routine", target = routine.id),
                    isLocked = isDeviceLocked(),
                    passed = true,
                    summary = "Executed routine '${routine.triggerPhrase}' (${routine.actions.size} tool calls)"
                )
            }
        }
    }

    private fun executeAndCompleteAction(action: VoiceAction, isLockedState: Boolean) {
        _voiceState.value = VoiceState.ExecutingAction(action)
        _lastExecutedAction.value = action

        val canonicalName = com.omnivoice.agentfix.ToolDispatcher.canonicalToolName(action.action)
        val isReturnCmd = canonicalName == "return_to_origin" ||
            (action.action.equals("navigate", ignoreCase = true) &&
                action.target.lowercase() in setOf("return_to_origin", "previous_app", "origin_task"))
        val isOpenOrReturnCmd = isReturnCmd || canonicalName == "open_app"
        if (isOpenOrReturnCmd) {
            com.omnivoice.agentfix.UiTools.clearLastCanonicalResult()
        }

        val result = actionExecutor.execute(action)
        val currentOutcome = com.omnivoice.agentfix.UiTools.lastCanonicalResult
        val utterance = _liveTranscription.value.ifBlank {
            com.omnivoice.agentfix.AgentSession.lastUserUtterance
        }

        when (result) {
            is ExecutionResult.Success -> {
                val diagnosticMsg = result.message.ifBlank { action.spokenResponse }
                val spoken = if (isOpenOrReturnCmd) {
                    omniLiveEngine.buildSpokenConfirmationForAppCommand(
                        outcome = currentOutcome,
                        utterance = utterance,
                        isReturnCommand = isReturnCmd,
                        fallbackAppName = action.target
                    )
                } else {
                    omniLiveEngine.sanitizeSpokenText(diagnosticMsg, utterance)
                }
                speakResponse(spoken)
                _voiceState.value = VoiceState.Idle
                recordLog(action, isLocked = isLockedState, passed = true, summary = diagnosticMsg, executionStatus = "VERIFIED")
            }
            is ExecutionResult.RequestedUnverified -> {
                val diagnosticMsg = result.message
                val spoken = if (isOpenOrReturnCmd) {
                    omniLiveEngine.buildSpokenConfirmationForAppCommand(
                        outcome = currentOutcome,
                        utterance = utterance,
                        isReturnCommand = isReturnCmd,
                        fallbackAppName = action.target
                    )
                } else {
                    omniLiveEngine.sanitizeSpokenText(diagnosticMsg, utterance)
                }
                speakResponse(spoken)
                _voiceState.value = VoiceState.RequestedUnverified(diagnosticMsg, result.reasonCode)
                recordLog(action, isLocked = isLockedState, passed = false, summary = diagnosticMsg, executionStatus = "REQUESTED_UNVERIFIED")
            }
            is ExecutionResult.Failure -> {
                val diagnosticMsg = result.error
                val spoken = if (isOpenOrReturnCmd) {
                    omniLiveEngine.buildSpokenConfirmationForAppCommand(
                        outcome = currentOutcome,
                        utterance = utterance,
                        isReturnCommand = isReturnCmd,
                        fallbackAppName = action.target
                    )
                } else {
                    omniLiveEngine.sanitizeSpokenText(diagnosticMsg, utterance)
                }
                speakResponse(spoken)
                _voiceState.value = VoiceState.Error(diagnosticMsg)
                recordLog(action, isLocked = isLockedState, passed = false, summary = diagnosticMsg, executionStatus = "FAILED")
            }
        }
    }

    fun undoLastAction() {
        val last = _lastExecutedAction.value ?: return
        val res = actionExecutor.undoAction(last)
        val msg = when (res) {
            is ExecutionResult.Success -> res.message
            is ExecutionResult.RequestedUnverified -> res.message
            is ExecutionResult.Failure -> res.error
        }
        _lastExecutedAction.value = null
        speakResponse(omniLiveEngine.sanitizeSpokenText(msg, _liveTranscription.value))
    }

    fun speakResponse(text: String, onDone: (() -> Unit)? = null) {
        val cleanText = omniLiveEngine.sanitizeSpokenText(text, _liveTranscription.value)
        _spokenResponse.value = cleanText
        // Strictly mute microphone while TTS is speaking so the AI never hears its own voice
        sttManager.stopListening()
        // Single-owner audio rule: if Online Gemini Live WebSocket is connected or actively speaking 24kHz audio,
        // cancel local TTS so two voices never overlap and Live session remains the single speaker
        if (omniLiveEngine.isGeminiLiveWebSocketConnected() ||
            (isLiveSessionActive.value && liveConnectionState.value == LiveConnectionState.LIVE_AI_SPEAKING)
        ) {
            ttsManager.stop()
            onDone?.invoke()
            return
        }
        audioStateMachine.transitionTo(AudioPipelineOwner.SPEAKING)

        val locale = _detectedLanguage.value.ttsLocale
        ttsManager.onSpeechCompleted = {
            audioStateMachine.transitionTo(AudioPipelineOwner.IDLE)
            onDone?.invoke()
            // Automatically resume listening as soon as TTS finishes in Hands-Free Continuous Mode
            if (!isLiveSessionActive.value && (_isMicLockedAlwaysListening.value || _isHandsFreeAlwaysOn.value) && onDone == null) {
                viewModelScope.launch {
                    delay(350)
                    if (!isLiveSessionActive.value && (_isMicLockedAlwaysListening.value || _isHandsFreeAlwaysOn.value)) {
                        startListeningForCommand()
                    }
                }
            }
        }
        ttsManager.speak(cleanText, _config.value.voicePitch, _config.value.voiceSpeed, locale)
    }

    private fun recordLog(
        action: VoiceAction,
        isLocked: Boolean,
        passed: Boolean,
        summary: String,
        executionStatus: String = if (passed) "SUCCESS" else "FAILED"
    ) {
        val rawCommand = _pendingQuery.value.ifBlank { action.target }
        val entry = SecurityLogEntry(
            commandText = rawCommand,
            actionType = action.action,
            isLockedState = isLocked,
            verificationPassed = passed,
            responseSummary = summary
        )
        _auditLogs.value = listOf(entry) + _auditLogs.value

        // Persist into Room database
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.recordCommand(
                    CommandHistory(
                        rawSpeech = rawCommand,
                        actionType = action.action,
                        target = action.target,
                        isLockedMode = isLocked,
                        verificationPassed = passed,
                        responseSummary = summary,
                        executionStatus = executionStatus
                    )
                )
            } catch (e: Exception) {
                Log.w("VoiceAssistantViewModel", "Failed to persist command in Room", e)
            }
        }
    }

    // --- Personal Vocabulary, Long-Term Memory, and 200+ Benchmark Suite ---

    fun teachPersonalVocabularyFromUi(
        spokenPhrase: String,
        canonicalMeaning: String,
        urduScript: String = "",
        category: String = "custom_command"
    ) {
        if (spokenPhrase.isBlank() || canonicalMeaning.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.teachPersonalWord(
                spokenPhrase = spokenPhrase,
                canonicalMeaning = canonicalMeaning,
                urduScript = urduScript,
                category = category
            )
        }
    }

    fun deletePersonalVocabulary(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePersonalVocabulary(id)
        }
    }

    fun saveUserMemoryFromUi(key: String, value: String, type: String = "preference") {
        if (key.isBlank() || value.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveUserMemory(key, value, type)
        }
    }

    fun deleteUserMemory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteUserMemory(id)
        }
    }

    fun promoteFailedCommand(logId: Long, spokenPhrase: String, canonicalMeaning: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.promoteFailedCommandToVocabulary(logId, spokenPhrase, canonicalMeaning)
        }
    }

    fun runBilingual200Benchmark() {
        viewModelScope.launch(Dispatchers.Default) {
            val summary = BilingualBenchmarkRunner.runFullSuite()
            _benchmarkSummary.value = summary
        }
    }

    private fun checkLockoutTimer() {
        if (securityManager.isLockedOut()) {
            startLockoutCountdown()
        }
    }

    private fun startLockoutCountdown() {
        lockoutCountdownJob?.cancel()
        lockoutCountdownJob = viewModelScope.launch {
            while (isActive && securityManager.isLockedOut()) {
                _lockoutSeconds.value = securityManager.getRemainingLockoutSeconds()
                delay(1000)
            }
            _lockoutSeconds.value = 0L
        }
    }

    // --- Configuration & Security Setup Methods ---

    fun enrollPassphrase(passphrase: String) {
        securityManager.enrollPassphrase(passphrase)
        _hasPassphrase.value = true
        speakResponse("Security passphrase successfully saved.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun setStoredUnlockPin(pin: String) {
        // Phase 01 Security Policy: Device PIN collection, storage, and replay are disabled
        _storedPin.value = ""
    }

    fun enrollVoiceSamples(samples: List<ShortArray>) {
        val profile = securityManager.enrollVoiceProfile(samples)
        _enrolledProfile.value = profile
        speakResponse("Voice biometric profile enrolled successfully.")
    }

    fun clearVoiceBiometrics() {
        securityManager.clearBiometrics()
        _enrolledProfile.value = VoiceEnrollmentProfile()
        speakResponse("Voice profile cleared.")
    }

    fun resetLockout() {
        securityManager.resetFailedAttempts()
        _lockoutSeconds.value = 0L
        lockoutCountdownJob?.cancel()
    }

    fun updateConfig(newConfig: AssistantConfig) {
        _config.value = newConfig
        WakeWordForegroundService.configuredWakeWord = newConfig.wakeWord
    }

    fun toggleForegroundService(enable: Boolean) {
        if (enable) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMic) {
                _voiceState.value = VoiceState.Error(
                    "Microphone Permission Required",
                    "Please grant Microphone permission in the Setup tab first."
                )
                speakResponse("Microphone permission is required to enable background listening.")
                return
            }
            WakeWordForegroundService.start(context)
        } else {
            WakeWordForegroundService.stop(context)
        }
    }

    // --- AI Studio 7 Gemini Skills State & Actions ---

    private val _chatMessages = MutableStateFlow<List<GeminiChatMessage>>(
        listOf(
            GeminiChatMessage(
                role = "model",
                text = "Assalam-o-Alaikum & Hello! I am your multi-model Gemini Chatbot. Switch between Gemini 4 Argon (complex reasoning), Gemini 3.8 Flash (general + Search & Maps grounding), and Gemini 3.1 Flash-Lite (fast tasks).",
                modelUsed = GeminiChatModel.GEMINI_3_8_FLASH.modelId
            )
        )
    )
    val chatMessages: StateFlow<List<GeminiChatMessage>> = _chatMessages.asStateFlow()

    private val _selectedChatModel = MutableStateFlow(GeminiChatModel.GEMINI_3_8_FLASH)
    val selectedChatModel: StateFlow<GeminiChatModel> = _selectedChatModel.asStateFlow()

    private val _chatSystemInstruction = MutableStateFlow(
        "You are OmniVoice AI Studio Assistant. Reply helpfully in the user's preferred language (English, Urdu, or Roman Urdu)."
    )
    val chatSystemInstruction: StateFlow<String> = _chatSystemInstruction.asStateFlow()

    private val _enableSearchGrounding = MutableStateFlow(true)
    val enableSearchGrounding: StateFlow<Boolean> = _enableSearchGrounding.asStateFlow()

    private val _enableMapsGrounding = MutableStateFlow(false)
    val enableMapsGrounding: StateFlow<Boolean> = _enableMapsGrounding.asStateFlow()

    private val _thinkingConfig = MutableStateFlow(
        ThinkingConfig(
            enabled = true,
            includeThoughts = true,
            thinkingLevel = ThinkingLevel.HIGH
        )
    )
    val thinkingConfig: StateFlow<ThinkingConfig> = _thinkingConfig.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private val _generatedImages = MutableStateFlow<List<ImageGenerationResult>>(emptyList())
    val generatedImages: StateFlow<List<ImageGenerationResult>> = _generatedImages.asStateFlow()

    private val _isGeneratingImage = MutableStateFlow(false)
    val isGeneratingImage: StateFlow<Boolean> = _isGeneratingImage.asStateFlow()

    private val _generatedVideos = MutableStateFlow<List<VideoAnimationResult>>(emptyList())
    val generatedVideos: StateFlow<List<VideoAnimationResult>> = _generatedVideos.asStateFlow()

    private val _isGeneratingVideo = MutableStateFlow(false)
    val isGeneratingVideo: StateFlow<Boolean> = _isGeneratingVideo.asStateFlow()

    private val _transcribedNotes = MutableStateFlow<List<String>>(emptyList())
    val transcribedNotes: StateFlow<List<String>> = _transcribedNotes.asStateFlow()

    private val _isTranscribingAudio = MutableStateFlow(false)
    val isTranscribingAudio: StateFlow<Boolean> = _isTranscribingAudio.asStateFlow()

    fun selectChatModel(model: GeminiChatModel) {
        _selectedChatModel.value = model
        if (model == GeminiChatModel.GEMINI_4_ARGON && !_thinkingConfig.value.enabled) {
            _thinkingConfig.value = _thinkingConfig.value.copy(enabled = true)
        }
    }

    fun toggleThinkingMode(enabled: Boolean) {
        _thinkingConfig.value = _thinkingConfig.value.copy(enabled = enabled)
    }

    fun selectThinkingLevel(level: ThinkingLevel) {
        _thinkingConfig.value = _thinkingConfig.value.copy(
            thinkingLevel = level,
            thinkingBudget = level.defaultBudgetTokens
        )
    }

    fun updateChatSystemInstruction(instruction: String) {
        _chatSystemInstruction.value = instruction
    }

    fun toggleSearchGrounding(enabled: Boolean) {
        _enableSearchGrounding.value = enabled
    }

    fun toggleMapsGrounding(enabled: Boolean) {
        _enableMapsGrounding.value = enabled
    }

    fun clearChatHistory() {
        _chatMessages.value = listOf(
            GeminiChatMessage(
                role = "model",
                text = "Conversation history cleared. Ready for your next question!",
                modelUsed = _selectedChatModel.value.modelId
            )
        )
    }

    fun sendStudioChatMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isBlank() || _isChatLoading.value) return

        val previousHistory = _chatMessages.value
        val userMsg = GeminiChatMessage(role = "user", text = trimmed)
        _chatMessages.value = previousHistory + userMsg
        _isChatLoading.value = true

        viewModelScope.launch {
            try {
                val reply = geminiSkills.sendChatMessage(
                    history = previousHistory,
                    newUserMessage = trimmed,
                    selectedModel = _selectedChatModel.value,
                    systemInstruction = _chatSystemInstruction.value,
                    enableGoogleSearch = _enableSearchGrounding.value,
                    enableGoogleMaps = _enableMapsGrounding.value,
                    thinkingConfig = _thinkingConfig.value
                )
                _chatMessages.value = _chatMessages.value + reply
            } finally {
                _isChatLoading.value = false
            }
        }
    }

    fun createOrEditImageWithGemini(prompt: String, sourceBitmap: Bitmap?) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank() || _isGeneratingImage.value) return
        _isGeneratingImage.value = true

        viewModelScope.launch {
            try {
                val res = geminiSkills.generateOrEditImage(trimmed, sourceBitmap)
                _generatedImages.value = listOf(res) + _generatedImages.value
            } finally {
                _isGeneratingImage.value = false
            }
        }
    }

    fun animatePhotoIntoVideo(sourceBitmap: Bitmap, prompt: String, aspectRatio: VideoAspectRatio) {
        if (_isGeneratingVideo.value) return
        _isGeneratingVideo.value = true

        viewModelScope.launch {
            try {
                val res = geminiSkills.animateImageIntoVideo(sourceBitmap, prompt, aspectRatio)
                _generatedVideos.value = listOf(res) + _generatedVideos.value
            } finally {
                _isGeneratingVideo.value = false
            }
        }
    }

    fun startGeminiLiveConversation(voiceName: String = "Aoede") {
        WakeWordForegroundService.pause(context)
        sttManager.stopListening()
        geminiSkills.startLiveVoiceSession(voiceName) { fallbackSpokenText ->
            speakResponse(fallbackSpokenText)
        }
    }

    fun stopGeminiLiveConversation() {
        geminiSkills.stopLiveVoiceSession()
        WakeWordForegroundService.resume(context)
    }

    fun sendGeminiLiveTextTurn(text: String) {
        geminiSkills.sendLiveVoiceTextTurn(text) { fallbackSpokenText ->
            speakResponse(fallbackSpokenText)
        }
    }

    fun startStudioAudioRecording() {
        WakeWordForegroundService.pause(context)
        sttManager.stopListening()
        geminiSkills.startRecordingForTranscription()
    }

    fun stopStudioAudioRecordingAndTranscribe(languageMode: String) {
        _isTranscribingAudio.value = true
        viewModelScope.launch {
            try {
                val transcript = geminiSkills.stopAndTranscribeAudio(languageMode)
                if (transcript.isNotBlank()) {
                    _transcribedNotes.value = listOf(transcript) + _transcribedNotes.value
                }
            } finally {
                _isTranscribingAudio.value = false
                WakeWordForegroundService.resume(context)
            }
        }
    }

    // --- Official Gemini Live Session Control Methods ---

    fun refreshWakeAndSpeechState(): com.example.engine.WakeAndAssistantReadinessReport {
        return AssistantAudioStateMachine.shared.inspectWakeAndAssistantReadiness(context)
    }

    /**
     * Arms the visible "Jarvis" wake-only background service after user action/consent.
     */
    fun armJarvisWakeAndSpeechSession(): Boolean {
        val started = WakeWordForegroundService.start(context, userInitiatedArm = true)
        if (started) {
            _isHandsFreeAlwaysOn.value = true
            audioStateMachine.armWakeWordListening()
        }
        refreshWakeAndSpeechState()
        return started
    }

    /**
     * Working Stop Action: stops Gemini Live, stops STT, stops TTS, stops WakeWordForegroundService,
     * releases microphone ownership, and pins `isStoppedByUser = true, isUserArmed = false` to device storage.
     */
    fun stopAllWakeAndSpeechAndPinToDevice() {
        _isHandsFreeAlwaysOn.value = false
        _isMicLockedAlwaysListening.value = false
        sttManager.setContinuousLockMode(false)
        sttManager.stopListening()
        ttsManager.stop()
        omniLiveEngine.endLiveSession()
        WakeWordForegroundService.stop(context, pinUserStop = true)
        audioStateMachine.disarmWakeWordListening()
        _voiceState.value = VoiceState.Idle
        refreshWakeAndSpeechState()
    }

    fun startGeminiLiveSession(voice: GeminiLiveVoice = selectedLiveVoice.value) {
        AssistantAudioStateMachine.shared.armUserSessionAndPersist(context, "USER_STARTED_GEMINI_LIVE")
        WakeWordForegroundService.pause(context, pinDeviceMute = false)
        sttManager.stopListening()
        ttsManager.stop()
        OmniAutonomousCoreEngine.resetInactivityTimer()
        if (isLiveSessionActive.value && operatingMode.value != OmniOperatingMode.STATE_SLEEP_STANDBY) {
            return
        }
        omniLiveEngine.selectVoice(voice)
        omniLiveEngine.startLiveSession()
    }

    fun endGeminiLiveSession() {
        stopAllWakeAndSpeechAndPinToDevice()
    }

    fun wakeUpAssistantFromStandby() {
        sttManager.stopListening()
        ttsManager.stop()
        omniLiveEngine.wakeUpFromStandby()
    }

    fun enterStandbyModeNow() {
        omniLiveEngine.enterStandbySleepMode()
    }

    fun toggleSimulatedOfflineMode() {
        val next = !OmniAutonomousCoreEngine.isSimulatedOffline.value
        OmniAutonomousCoreEngine.setSimulatedOfflineMode(context, next)
    }

    fun renameAssistantPermanently(newName: String) {
        val cleaned = newName.trim()
        if (cleaned.isBlank()) return
        OmniAutonomousCoreEngine.learnAndRememberPermanently(
            context = context,
            category = "assistant_name",
            ruleOrPreference = "Assistant renamed to $cleaned",
            newAssistantName = cleaned
        )
        WakeWordForegroundService.configuredWakeWord = cleaned
    }

    fun completeStartupPermissionsAndContactsGatekeeper() {
        viewModelScope.launch(Dispatchers.IO) {
            androidBridge.requestAllRuntimePermissions()
            omniLiveEngine.syncAndroidPhoneContacts()
            OmniAutonomousCoreEngine.markStartupGatekeeperCompleted(context)
        }
    }

    fun toggleGeminiLiveSession() {
        if (operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY) {
            wakeUpAssistantFromStandby()
            return
        }
        if (isLiveSessionActive.value) {
            endGeminiLiveSession()
        } else {
            startGeminiLiveSession()
        }
    }

    fun toggleLiveMicMute() {
        omniLiveEngine.toggleMuteMic()
    }

    fun cycleLiveVoice() {
        omniLiveEngine.cycleVoice()
    }

    fun selectLiveVoice(voice: GeminiLiveVoice) {
        omniLiveEngine.selectVoice(voice)
    }

    fun sendTextToLiveSession(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        omniLiveEngine.sendTextMessageToLiveSession(trimmed)
    }

    fun dismissLiveSlideUpCard(cardId: Long) {
        omniLiveEngine.dismissSlideUpCard(cardId)
    }

    fun saveOrUpdateSmartContact(
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) {
        omniLiveEngine.saveOrUpdateSmartContact(
            name = name,
            phoneNumber = phoneNumber,
            whatsappNumber = whatsappNumber,
            instagramHandle = instagramHandle,
            facebookProfile = facebookProfile,
            tiktokHandle = tiktokHandle
        )
    }

    fun updateSmartContactById(
        contactId: Long,
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) {
        omniLiveEngine.updateSmartContactById(
            contactId = contactId,
            name = name,
            phoneNumber = phoneNumber,
            whatsappNumber = whatsappNumber,
            instagramHandle = instagramHandle,
            facebookProfile = facebookProfile,
            tiktokHandle = tiktokHandle
        )
    }

    fun deleteSmartContact(contactId: Long) {
        omniLiveEngine.deleteSmartContact(contactId)
    }

    fun getRawOmniContactsDbJson(): String {
        return omniLiveEngine.getRawOmniContactsDbJson()
    }

    fun importOmniContactsDbJson(rawJson: String) {
        omniLiveEngine.importOmniContactsDbJson(rawJson)
    }

    fun syncAndroidPhoneContacts(): Int {
        viewModelScope.launch(Dispatchers.IO) {
            omniLiveEngine.syncAndroidPhoneContacts()
        }
        return smartContacts.value.size
    }

    fun importVCardContacts(vcfText: String): Int {
        return omniLiveEngine.importVCardContacts(vcfText).size
    }

    fun getAutomationWebhookUrl(): String {
        return omniLiveEngine.getAutomationWebhookUrl()
    }

    fun setAutomationWebhookUrl(url: String) {
        omniLiveEngine.setAutomationWebhookUrl(url)
    }

    fun launchLiveCardDeepLinkNow(card: LiveSlideUpCard) {
        omniLiveEngine.launchCardDeepLinkNow(card)
    }

    override fun onCleared() {
        super.onCleared()
        omniLiveEngine.endLiveSession()
        geminiSkills.stopLiveVoiceSession()
        audioStateMachine.transitionTo(AudioPipelineOwner.IDLE)
        sttManager.stopListening()
        ttsManager.shutdown()
        lockoutCountdownJob?.cancel()
    }
}
