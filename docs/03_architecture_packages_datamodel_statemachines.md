# Phase 0 (c): System Architecture, Packages, Data Model & State Machines

---

## 1. Pinned Version Catalog & API Level Baseline

All dependencies are centrally pinned in `gradle/libs.versions.toml` with zero NDK/C++ compilation:

- **Android SDK Levels:**
  - `minSdk = 26` (Android 8.0 Oreo)
  - `targetSdk = 36` (Android 16 / latest stable API)
  - `compileSdk = 36`
- **Pinned Toolchain & Libraries (`gradle/libs.versions.toml`):**
  - Android Gradle Plugin (`agp`): `9.1.1`
  - Kotlin (`kotlin`): `2.2.10`
  - KSP (`googleDevtoolsKsp`): `2.3.5`
  - Jetpack Compose BOM (`composeBom`): `2024.09.00` (Material 3, UI, Graphics, Tooling, Navigation `2.8.9`, Activity Compose `1.10.1`, Lifecycle `2.8.7`)
  - Room Database (`roomRuntime`, `roomKtx`, `roomCompiler`): `2.7.0`
  - DataStore Preferences (`datastorePreferences`): `1.1.7`
  - Coroutines (`kotlinxCoroutinesAndroid`, `kotlinxCoroutinesCore`): `1.10.2`
  - Firebase BOM (`firebaseBom`): `34.17.0` (`firebase-ai` for Gemini + App Check)
  - Networking (`okhttp`): `4.10.0` (WebSocket `BidiGenerateContent` & model download manager)
  - CameraX (`cameraCore`, `cameraCamera2`, `cameraLifecycle`, `cameraView`): `1.5.0`
  - Testing (`junit` `4.13.2`, `robolectric` `4.16.1`, `kotlinxCoroutinesTest` `1.10.2`)

### API-Level Guard Matrix (Every API > 26 Annotated & Guarded)
- **API 26 (Android 8.0):** Baseline (`AudioFocusRequest`, `NotificationChannel`, `TelecomManager.acceptRingingCall`, `startForegroundService`, `ContactsContract`, `CameraManager.setTorchMode`).
- **API 27 (Android 8.1):** `Activity.setShowWhenLocked(true)`, `Activity.setTurnScreenOn(true)` (fallback to `WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED` on API 26).
- **API 28 (Android 9.0):** `TelecomManager.endCall()`, `AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN`, `BiometricPrompt`.
- **API 29 (Android 10):** `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, `RoleManager.ROLE_ASSISTANT`, `WifiNetworkSpecifier`, `WifiNetworkSuggestion`, `foregroundServiceType="microphone"`.
- **API 30 (Android 11):** `AccessibilityService.takeScreenshot()`, `<queries>` package visibility, `WindowInsetsController`.
- **API 31 (Android 12):** `TelephonyCallback`, `AudioManager.setCommunicationDevice()`, `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`, `SCHEDULE_EXACT_ALARM`, `android:isAccessibilityTool="true"`.
- **API 33 (Android 13):** `SpeechRecognizer.createOnDeviceSpeechRecognizer()`, `checkRecognitionSupport()`, `triggerModelDownload()`, `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES`, `ClipDescription.EXTRA_IS_SENSITIVE`.
- **API 34+ (Android 14/15/16):** Strict `FOREGROUND_SERVICE_MICROPHONE` start rules (`ForegroundServiceStartNotAllowedException` handling).

---

## 2. Package & Modular Architecture (Single-Activity, Single-Module `:app`)

```text
com.example
├── MainActivity.kt                        // Single Activity, Compose Navigation host, lock-screen flags
├── data
│   ├── local
│   │   ├── OmniVoiceDatabase.kt           // Room Database (v3)
│   │   ├── OmniVoiceRepository.kt         // Single source of truth for Memory, History, Routines, Vocab
│   │   ├── dao/                           // Room DAOs (MemoryDao, RoutineDao, HistoryDao, AuditDao, WifiDao)
│   │   └── entity/                        // Room Entities (UserMemory, VoiceRoutineEntity, AuditLogEntity, etc.)
│   ├── model
│   │   ├── ActionResult.kt                // Sealed class: Success, Failed, NeedsPermission, NeedsConfirmation, InProgress
│   │   ├── VoiceAction.kt                 // Canonical tool call + riskLevel + requiresInternet + requiresUnlock
│   │   ├── VoiceState.kt                  // UI & pipeline state representation
│   │   └── AssistantConfig.kt             // Name, wake phrase, timeout, language mode, privacy mode
│   └── security
│       ├── KeystoreCryptoManager.kt       // Android Keystore AES-256-GCM field encryption (PINs, Wi-Fi passwords)
│       ├── SecurityManager.kt             // PBKDF2 passphrase hash, MFCC voice print, 3-strike lockout, anti-replay
│       └── PromptInjectionFirewall.kt     // Wraps screen/notification/web text in <untrusted_data> & strips injections
├── engine
│   ├── audio
│   │   ├── AssistantAudioStateMachine.kt  // Single-owner mic & speaker state machine
│   │   ├── AudioCaptureEngine.kt          // 16kHz PCM16 AudioRecord + AEC/NS/AGC + VAD + 300ms pre-roll ring buffer
│   │   ├── AudioOutputManager.kt          // Singleton output owner: 24kHz PCM jitter buffer + TTS + turnId dedupe
│   │   ├── OnDeviceWakeWordEngine.kt      // VAD-gated custom-name wake detector ("<Name>, wake up" / "jaag jao")
│   │   └── EchoDiagnosticsRunner.kt       // Tone playback + mic correlation echo test for Diagnostics
│   ├── speech
│   │   ├── MultiEngineSpeechManager.kt    // STT fallback chain (Online Gemini -> Offline API 33/Whisper -> Classic SR)
│   │   ├── OnDeviceSpellingRecognizer.kt  // Offline-only STT spelling parser for Wi-Fi passwords & PINs
│   │   ├── TextToSpeechManager.kt         // Offline Urdu/English TTS + voice installer check + mood prosody
│   │   └── SpeechNormaliser.kt            // Produces displayText vs speechText, expands numbers/times, script split
│   ├── nlp
│   │   ├── LanguageProcessor.kt           // Urdu/English/Roman-Urdu detector, number/time/date/phone normaliser
│   │   ├── DeterministicCommandParser.kt  // Fast local parser + commands_dataset.json matcher (300+ commands)
│   │   └── DatasetRetriever.kt            // Loads vocabulary.json, commands_dataset.json, stt_corrections.json
│   ├── brain
│   │   ├── HybridBrainRouter.kt           // Local-first router + Privacy Mode gate + Cloud fallback
│   │   ├── OnDeviceLlmEngine.kt           // AICore Gemini Nano + LiteRT-LM (.litertlm) + Model Download Manager
│   │   ├── OmniLiveSessionEngine.kt       // Cloud Gemini Live WebSocket + Flash/Pro + Google Search Grounding
│   │   ├── ToolRegistry.kt                // Strict schema validator, risk classifier & ActionVerifier binding
│   │   └── AgentLoopEngine.kt             // Observe -> Decide -> Act -> Verify loop for multi-step & UI tasks
│   ├── execution
│   │   ├── ActionExecutor.kt              // L1->L2->L3->L4 Control Ladder executor
│   │   ├── ActionVerifier.kt              // Post-condition verifiers for calls, SMS, WhatsApp, alarms, settings, Wi-Fi
│   │   ├── ContactRepository.kt           // Full ContactsContract + WhatsApp MIME rows + ContentObserver + fuzzy match
│   │   ├── InstalledAppRegistry.kt        // PackageManager indexer + Urdu/Roman-Urdu app aliases
│   │   └── ShizukuPrivilegedExecutor.kt   // Optional L3 WRITE_SECURE_SETTINGS & Shizuku command runner
│   ├── connectivity
│   │   ├── NetworkMonitor.kt              // Real-time ConnectivityManager + NET_CAPABILITY_VALIDATED + captive portal
│   │   └── ConnectivityAssistantEngine.kt // 9-step Pending Task -> Wi-Fi/Data -> Scan -> Password -> Auto-Resume
│   └── mood
│       └── MoodAnalyzerEngine.kt          // Lexicon + acoustic prosody + context EMA mood estimator & safety helpline
├── service
│   ├── WakeWordForegroundService.kt       // Microphone FGS, Sleep/Wake/Stop notification, call pause, boot safety
│   ├── OmniAccessibilityService.kt        // Node finder, ACTION_SET_TEXT, gestures, OEM selector packs, self-healing
│   ├── OmniNotificationListenerService.kt // Incoming WhatsApp/SMS reader & RemoteInput background reply
│   ├── OmniVoiceInteractionService.kt     // Default Assistant Role lock-screen trigger
│   ├── OmniDeviceAdminReceiver.kt         // Optional Device Admin lock/security receiver
│   └── BootCompletedReceiver.kt           // Boot receiver + WorkManager watchdog
└── ui
    ├── navigation/                        // Type-safe Compose navigation (Home, History, Routines, Memory, Settings)
    ├── screens/
    │   ├── OmniOrbScreen.kt               // Home Orb, standby ring, Nastaliq/Latin transcript, verified action cards
    │   ├── PermissionWizardScreen.kt      // First-run step-by-step permission wizard + Red/Green Fix-This dashboard
    │   ├── DiagnosticsScreen.kt           // Mic/AEC/Echo test, STT/TTS voices, LLM speed, network & permission status
    │   ├── MemoryAndTeachingScreen.kt     // Search/edit/delete learned facts, aliases, routines, encrypted backup
    │   ├── HistoryAndAuditScreen.kt       // Verified ActionResult history & security audit log
    │   ├── RoutinesScreen.kt              // Custom multi-step voice macros & conditional triggers
    │   └── SettingsScreen.kt              // Name, wake phrase, timeout, language, voice preview, Privacy Mode, models
    ├── theme/                             // Material 3 Dark/Light + Jarvis HUD theme + Noto Nastaliq Urdu typography
    └── viewmodel/
        └── VoiceAssistantViewModel.kt     // MVVM state holder coordinating engines & UI
```

---

## 3. Core Data Models & Room + Keystore Schema

### 3.1 `ActionResult` Sealed Hierarchy (`Never Fake Success`)
```kotlin
sealed class ActionResult {
    data class Success(
        val detail: String,
        val verifiedBy: String,          // e.g., "TelephonyManager.CALL_STATE_OFFHOOK", "WifiManager.SSID_VALIDATED"
        val undoAction: VoiceAction? = null
    ) : ActionResult()

    data class Failed(
        val reason: String,
        val failedTier: String,          // "L1", "L2", "L3"
        val nextStepHint: String = ""
    ) : ActionResult()

    data class NeedsPermission(
        val permissionName: String,
        val humanExplanation: String,
        val settingsIntentAction: String
    ) : ActionResult()

    data class NeedsConfirmation(
        val promptMessage: String,
        val riskLevel: RiskLevel,
        val pendingAction: VoiceAction
    ) : ActionResult()

    data class InProgress(
        val currentStage: String,
        val stepIndex: Int,
        val totalSteps: Int
    ) : ActionResult()
}
```

### 3.2 Room Database Entities (`OmniVoiceDatabase`)
1. **`UserMemory` (`user_memories`)**:
   - `id: Long`, `memoryKey: String`, `memoryValue: String` (encrypted via `KeystoreCryptoManager` when `isSensitive = true`), `category: String` (`"fact"`, `"preference"`, `"alias"`, `"wifi_credential"`, `"conversation_summary"`, `"mood_summary"`), `createdAt: Long`, `updatedAt: Long`.
2. **`PersonalVocabulary` (`personal_vocabulary`)**:
   - `id: Long`, `spokenPhrase: String`, `canonicalMeaning: String`, `urduScript: String`, `category: String` (`"contact_nickname"`, `"app_alias"`, `"place_alias"`, `"stt_correction"`, `"custom_command"`), `usageCount: Int`.
3. **`RoutineEntity` (`routines`)**:
   - `id: String`, `triggerPhrase: String`, `triggerConditionJson: String` (supports voice trigger or event trigger like incoming call from X), `stepsJson: String` (ordered list of `VoiceAction` steps), `confirmationMessage: String`, `isEnabled: Boolean`, `lastExecutedAt: Long`.
4. **`CommandHistory` / `AuditLog` (`command_history`)**:
   - `id: Long`, `timestamp: Long`, `rawSpeech: String` (passwords/PINs masked as `***`), `detectedLanguage: String`, `actionType: String`, `target: String`, `controlTierUsed: String` (`"L1"`, `"L2"`, `"L3"`), `isLockedMode: Boolean`, `verificationPassed: Boolean`, `executionStatus: String` (`"SUCCESS"`, `"FAILED"`, `"NEEDS_PERMISSION"`, `"CANCELLED"`), `verifiedBy: String`, `responseSummary: String`.
5. **`FailedCommandLog` (`failed_command_logs`)**:
   - `id: Long`, `timestamp: Long`, `rawUtterance: String`, `normalizedUtterance: String`, `detectedLanguage: String`, `failureReason: String`, `resolvedByRuleId: Long?`.

---

## 4. Deterministic System State Machines

### 4.1 Power & Standby State Machine (`Section 1`)
```text
                   [App Launch / Wake Phrase "<Name>, wake up" / "jaag jao"]
                                       │
                                       ▼
                     ┌───────────────────────────────────┐
                     │              ACTIVE               │
                     │ • Live full-duplex conversation   │
                     │ • 60s countdown (idleSeconds = 60)│
                     │ • Paused while user speaks, AI    │
                     │   speaks, task runs, or question  │
                     │   awaits answer                   │
                     └─────────────────┬─────────────────┘
                                       │
       [60s silence elapsed OR user says "standby" / "so jao" / "sleep mode"]
       [Speaks: "Standby mode mein ja raha hoon. Jagane ke liye '<Name>, wake up' kahein."]
                                       │
                                       ▼
                     ┌───────────────────────────────────┐
                     │             STANDBY               │
                     │ • Cloud WebSocket CLOSED          │
                     │ • Heavy STT & LLM STOPPED         │
                     │ • Only VAD-gated OnDeviceWakeWord │
                     │   detector active in FGS          │
                     └───────────────────────────────────┘
```

### 4.2 Single-Owner Audio Pipeline State Machine (`Section 3`)
```text
  ┌─────────┐   Wake / Launch   ┌───────────┐   VAD Speech Start   ┌───────────────┐
  │ STANDBY │ ────────────────► │ LISTENING │ ───────────────────► │ USER_SPEAKING │
  └─────────┘                   └───────────┘                      └───────┬───────┘
       ▲                              ▲                                    │
       │ 60s Timeout                  │ Turn Complete                      │ VAD Silence / End of Turn
       │                              │                                    ▼
       │                        ┌───────────┐                      ┌───────────────┐
       └─────────────────────── │ SPEAKING  │ ◄─────────────────── │THINKING_ACTING│
                                └───────────┘   AudioOutputManager └───────────────┘
                                      │         (Single TurnID)
                                      │
                                      └───► [User Barge-In Detected (> threshold)]
                                            • Flush AudioOutputManager immediately
                                            • Transition directly to USER_SPEAKING
```

### 4.3 Connectivity Assistant 9-Step State Machine (`Section 6`)
```text
[User requests Online Task while NetworkMonitor.isValidatedInternet == false]
       │
       ▼
Step 1-2: Save PendingTask -> Speak:
          "Aapka mobile abhi internet se connected nahi hai. Kya main WiFi ya mobile data on kar doon?"
       │
       ├──► User says "data on karo" ──► Enable Mobile Data (L3/L2) ──► Go to Step 7
       │
       ▼ User says "wifi on kar do"
Step 3:   Enable Wi-Fi via Control Ladder (L3 svc wifi -> L2 Settings.Panel/QuickSettings -> L1) & Verify Radio ON
       │
       ▼
Step 4:   Scan & Rank Networks (RSSI descending, label saved / secured / open) -> Speak:
          "Mujhe ye networks mile hain: 1. Home_WiFi (saved), 2. PTCL-Net (password chahiye)... Kaunsa connect karoon?"
       │
       ▼
Step 5:   User names target SSID
       ├──► If Saved / Open ──► Connect & Go to Step 7
       │
       ▼ If Secured & Not Saved
Step 6:   Switch to On-Device Spelling STT ONLY (Cloud Paused, Zero Logging)
          • Parse "capital A", "chhota b", "number 5", "underscore", "dash", "dot", Urdu letters, NATO words
          • Read back character-by-character -> Wait for "haan" / "theek hai"
          • Inject password via ACTION_SET_TEXT / sensitive clipboard / L3 cmd wifi & click Connect
          • If authentication error -> Retry up to 3 times
       │
       ▼
Step 7:   Verify SSID matches AND NetworkCapabilities.NET_CAPABILITY_VALIDATED == true
          • If Captive Portal / No Internet -> Report honestly & offer Mobile Data
       │
       ▼
Step 8:   Ask: "Kya main is WiFi ka password mehfooz (save) kar loon?" -> Save to Android Keystore if "haan"
       │
       ▼
Step 9:   Auto-Resume PendingTask -> Execute & Speak Verified ActionResult in user's language
```
