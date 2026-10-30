# Phase 0 (c): Architecture, Capability Probe Tiers, Data Model & State Machines

---

## 1. Pinned Version Catalog & SDK Configuration (`minSdk = 31`, `targetSdk = 36/37`)

- **`minSdk = 31` (Android 12)**: All legacy pre-API-31 code paths (`Build.VERSION.SDK_INT < Build.VERSION_CODES.S`) are eliminated.
- **Single-Activity, Single-Module (`:app`)**: Jetpack Compose + Material 3 UI (`MainActivity`), zero XML layouts, zero NDK/C++ source code.
- **Version-Aware Compat Layer (`com.example.engine.Android12To17Compat`)**:
  - Centralizes every SDK-specific check for **API 31 (Android 12)**, **API 33 (Android 13)**, **API 34 (Android 14)**, **API 35 (Android 15)**, **API 36 (Android 16)**, and **API 37 (Android 17)**:
    1. `isRoleAssistantHeld(context)` (`RoleManager.ROLE_ASSISTANT`)
    2. `canStartMicrophoneForegroundService(context)` (checks `RECORD_AUDIO` + visible state or `ROLE_ASSISTANT`)
    3. `isAdvancedProtectionEnabled(context)` (Android 17 `AdvancedProtectionManager` reflection/compat check)
    4. `isNodeAccessibilityDataSensitive(node)` (API 34+ `AccessibilityNodeInfo.isAccessibilityDataSensitive`)
    5. `isAutoRevokeWhitelisted(context)` (API 31+ `PackageManager.isAutoRevokeWhitelisted`)
    6. `setVolumeAndVerifyNoSuppression(context, stream, index)` (Android 17 background-audio suppression detector)

---

## 2. Any-Phone Capability Probe & 4 Hardware Tiers (`CapabilityProbe.kt`)

On first launch and inside **Diagnostics / Setup Health**, `CapabilityProbe.runProbe(context)` inspects the device and stores a `CapabilityProfile`:

| Tier | Hardware Criteria | Active Local & Cloud Stack |
| :--- | :--- | :--- |
| **Tier A (Flagship / High RAM)** | $\ge 8\text{ GB}$ RAM, `arm64-v8a`, modern SoC | On-device **Gemini Nano (AICore)** or **Gemma 3/4 (.litertlm INT4)** + On-device `SpeechRecognizer` (`ur-PK`, `en-US`) + Deterministic 300+ Command Parser + Cloud Gemini Live/Flash/Pro when online. |
| **Tier B (Mid-Range)** | $6\text{ GB} - 7.9\text{ GB}$ RAM, `arm64-v8a` | Compact **Gemma 1B/E2B (.litertlm)** + Android On-device `SpeechRecognizer` + Deterministic Command Parser + Cloud Gemini for heavy multi-step planning. |
| **Tier C (Budget / Low RAM / 32-bit)** | $\le 4\text{ GB}$ RAM or `armeabi-v7a` / Android Go | **Deterministic 300+ Command Parser** (< 50 ms, zero RAM overhead) + Android `SpeechRecognizer` / `TextToSpeech` + Cloud Gemini when online (on-device LLM switched off gracefully to prevent OOM crashes). |
| **Tier D (No Google Play Services / HMS)** | Missing `com.google.android.gms` (e.g. Huawei) | Local Deterministic Parser + Local TTS/STT + Direct HTTPS REST Gemini API client using user's Keystore-encrypted API key (bypasses GMS/Firebase dependency). |

Additional probed attributes in `CapabilityProfile`:
- `oemBrand` (`SAMSUNG_ONEUI`, `XIAOMI_HYPEROS_MIUI`, `OPPO_COLOROS`, `REALME_UI`, `VIVO_FUNTOUCH`, `INFINIX_XOS`, `TECNO_HIOS`, `PIXEL_STOCK`, `MOTOROLA`, `ONEPLUS_OXYGENOS`, `HUAWEI_EMUI_HARMONY`, `GENERIC_ANDROID`)
- `dualSimCount` & active SIM carrier names (`SIM 1`, `SIM 2`)
- `advancedProtectionActive` (Android 17 check)
- `shizukuInstalled` (`moe.shizuku.privileged.api`)
- `writeSecureSettingsGranted`

---

## 3. Package Structure

```text
com.example
├── MainActivity.kt                         // Single-Activity Compose host (Home, History, Routines, Memory, Settings + Setup Health)
├── data
│   ├── local
│   │   ├── OmniVoiceDatabase.kt            // Room DB: Routines, PersonalVocabulary, UserMemory, ActionAuditLog
│   │   ├── dao/                            // RoutineDao, PersonalVocabularyDao, UserMemoryDao
│   │   └── entity/                         // Routine, PersonalVocabulary, UserMemory
│   └── model/                              // VoiceAction, AssistantConfig, ConversationMessage
├── engine
│   ├── Android12To17Compat.kt              // API 31..37 behavior-change compat layer & AdvancedProtection detector
│   ├── CapabilityProbe.kt                  // Hardware Tier A/B/C/D classifier, OEM skin detector & DualSimManager
│   ├── AssistantAudioStateMachine.kt       // Single mic owner state machine + all-zero mic mute detector
│   ├── AudioOutputManager.kt               // Single audio output singleton, turnId dedupe, barge-in, Echo Test, SpeechNormaliser
│   ├── VoiceActivityDetector.kt            // 16kHz PCM VAD with pre-roll & silence hangover
│   ├── MultiEngineSpeechManager.kt         // Online Gemini Audio + On-Device SpeechRecognizer (ur-PK/en) + Error 11 backoff
│   ├── LanguageProcessor.kt                // Urdu Nastaliq / Roman Urdu / English / Mixed normaliser & fuzzy matcher
│   ├── ConnectivityAndSpellingEngine.kt    // OnDeviceSpellingRecognizer, ControlLadderExecutor, PromptInjectionFirewall, OnDeviceLlmAndModelManager
│   ├── MoodAndMemoryEngine.kt              // 9-state EMA MoodAnalyzerEngine + Helpline Guardrail + EncryptedMemoryBackupManager
│   ├── SecurityVaultManager.kt             // Salted passphrase hash, speaker biometrics, 3-strike lockout, Unlock Ladder
│   ├── ActionExecutor.kt & ActionVerifier.kt // 35+ verified device tools returning typed ActionResult
│   ├── OmniLiveSessionEngine.kt            // Full-duplex BidiGenerateContent Live Call engine
│   └── BilingualBenchmarkRunner.kt         // 324+ bilingual command benchmark runner
├── service
│   ├── WakeWordForegroundService.kt        // Always-On mic FGS started via ROLE_ASSISTANT / visible activity / notification tap
│   ├── OmniVoiceInteractionService.kt      // Default Digital Assistant service + session
│   ├── OmniRecognitionService.kt           // Platform RecognitionService required by Android to list OmniVoice as Assistant
│   ├── OmniAccessibilityService.kt         // L2 UI automation + sensitive node check + OEM selector pack runner
│   ├── OmniNotificationListenerService.kt  // Incoming WhatsApp/SMS reader & inline voice reply
│   ├── OmniDeviceAdminReceiver.kt          // Screen lock admin receiver (lockNow only)
│   └── BootCompletedReceiver.kt            // Safe Android 12-17 boot handler (posts 1-tap Wake notification if not ROLE_ASSISTANT)
└── ui
    ├── screens/                            // OmniOrbScreen, PermissionsSetupScreen, DiagnosticsScreen, VocabularyScreen, RoutinesScreen, HistoryLogScreen, SettingsHubScreen
    └── viewmodel/VoiceAssistantViewModel.kt
```

---

## 4. Core Deterministic State Machines

1. **Always-On Audio & Turn State Machine (`AssistantAudioStateMachine` + `AudioOutputManager`)**:
   - `STANDBY` (60s silence elapsed; only tiny wake-phrase detector running) $\xrightarrow{\text{"<Name>, wake up"}}$ `LISTENING` $\xrightarrow{\text{VAD onset}}$ `USER_SPEAKING` $\xrightarrow{\text{VAD end}}$ `THINKING_ACTING` $\xrightarrow{\text{acquireTurnOutput(turnId)}}$ `SPEAKING` $\xrightarrow{\text{completeTurnOutput / barge-in}}$ `LISTENING`.
2. **Connectivity Prerequisite Resolver (`OmniAutonomousCoreEngine`)**:
   - `IDLE` $\rightarrow$ `DETECTED_OFFLINE_SAVED_PENDING_TASK` $\rightarrow$ `AWAITING_WIFI_OR_DATA_CONFIRMATION` $\rightarrow$ `ENABLING_RADIO_VIA_CONTROL_LADDER` $\rightarrow$ `SCANNING_AND_LISTING_SSIDS` $\rightarrow$ `AWAITING_SSID_CHOICE` $\rightarrow$ `CAPTURING_SPELLED_PASSWORD_OFFLINE` $\rightarrow$ `READBACK_CONFIRMATION` $\rightarrow$ `CONNECTING_AND_VERIFYING_VALIDATED_NET` $\rightarrow$ `OFFER_KEYSTORE_SAVE` $\rightarrow$ `AUTO_RESUMING_PENDING_TASK`.
3. **Lock-Screen Verification & Unlock Ladder (`SecurityVaultManager`)**:
   - `LOCKED_TRIGGER` $\rightarrow$ `CHECK_RISK_LEVEL` (Low executes directly; Medium/High requires `SPOKEN_PASSPHRASE_CHALLENGE` + `SPEAKER_BIOMETRIC_MATCH`) $\rightarrow$ 3 failures trigger `LOCKOUT_5_MIN_INTRUDER_LOG` $\rightarrow$ on match executes `UNLOCK_LADDER` (`L1 requestDismissKeyguard` $\rightarrow$ `L2 Accessibility Keyguard PIN/Swipe` $\rightarrow$ `L3 Shizuku/ADB input` $\rightarrow$ `Smart Lock guidance`).
