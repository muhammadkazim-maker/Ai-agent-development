# Phase 0 (a): Master Traceability Matrix (`R1`–`R18` $\rightarrow$ Features $\rightarrow$ Modules $\rightarrow$ Tests)

> **Target Platform:** Android 12 (`API 31`) through Android 17 (`API 37`), `minSdk = 31`, single-Activity Jetpack Compose (`MainActivity`), zero XML layouts, zero NDK/C++ source code.

---

## 1. High-Level Requirement Traceability (`R1`–`R18`)

| Req ID | Source Requirement Summary | Mapped Feature IDs | Target Modules | Automated & Real-Phone Verification Tests |
| :--- | :--- | :--- | :--- | :--- |
| **R1** | **Always available**: answers whenever called (screen on, off, locked, any app in front, after reboot) and speaks every reply. | `F-001`–`F-012` | Module 1, Module 2, Module 11 | `AlwaysOnEngineRuleTest`, `BootNotificationFallbackTest`, Real-Phone Lock/Off Wake Test |
| **R2** | **Unlocked vs. Locked phone**: unlocked executes directly; locked requires spoken secret passphrase + speaker verification, and unlocks via Unlock Ladder. | `F-013`–`F-020` | Module 11 | `SecurityVaultPassphraseTest`, `LockoutAfterThreeFailuresTest`, Real-Phone Keyguard Unlock Test |
| **R3** | **Maximum possible control**: all contacts (including WhatsApp), calls, SMS, WhatsApp, all installed apps, settings, files, camera, alarms, navigation. | `F-021`–`F-045` | Module 5, Module 6 | `ContactRepositoryMultiAccountTest`, `AppRegistryAliasTest`, `CapabilitiesVerifierSuiteTest` |
| **R4** | **Zero touch**: taps, scrolls, and types (including spoken passwords) by itself via `OmniAccessibilityService` + Control Ladder. | `F-046`–`F-058` | Module 6, Module 7 | `AccessibilityEngineTreeTest`, `OnDeviceSpellingRecognizerTest`, `SensitiveClipboardPasteClearTest` |
| **R5** | **Offline-first**: every non-internet action (calls, SMS, alarms, settings, UI control, offline chat) works in Airplane Mode. | `F-059`–`F-066` | Module 2, Module 3, Module 6, Module 8 | `AirplaneModeOfflineExecutionTest`, `OfflineCommandEngineLatencyTest` (< 50 ms) |
| **R6** | **Online task without internet (Connectivity Assistant)**: detects no internet, saves pending task, asks to enable Wi-Fi/Data, scans & lists SSIDs, captures spoken password offline, connects, verifies `NET_CAPABILITY_VALIDATED`, and auto-resumes pending task. | `F-067`–`F-077` | Module 7 | `MockAccessibilityTreeWifiFlowTest`, `PrerequisiteResolverStepsAtoDTest` |
| **R7** | **English, Urdu (Nastaliq), Roman Urdu & Mixed without mistakes**: strict reply-language mirroring, normalisation (`saarhe teen`, `dhai baje`, `paune paanch`, `03xx -> +923xx`), cross-script fuzzy matching, large `assets/` datasets (`vocabulary.json`, `commands_dataset.json`, `stt_corrections.json`). | `F-078`–`F-088` | Module 4 | `BilingualBenchmark300PlusTest` (324+ commands across 4 styles, $\ge 95\%$ accuracy) |
| **R8** | **Thinks & plans like Gemini / ChatGPT**: multi-step task planning, conditional rules, Google Search grounding online, Gemini Nano / LiteRT-LM offline. | `F-089`–`F-096` | Module 8 | `ComplexTaskPlanThinkingTest`, `AgentLoopChainedConditionalTest` |
| **R9** | **Never says "done" unless it really happened**: typed `ActionResult` (`Success`, `Failed`, `NeedsPermission`, `NeedsConfirmation`, `InProgress`) with real state read-back verifiers. | `F-097`–`F-104` | Module 5, Module 6, Module 7 | `NeverFakeSuccessActionVerifierTest`, `HallucinatedFlashlightGuardrailTest` |
| **R10** | **One clear natural voice & stable mic**: fixes `Recognition error (11)`, unclear voice, and background double-voice/echo via single `AudioRecord` owner + `AudioOutputManager` singleton (`turnId` deduplication + modality lock + hardware `AEC`/`NS`/`AGC`). | `F-105`–`F-115` | Module 2, Module 3 | `DoubleVoiceRegressionTest` (`ActiveStreamCount == 1`), `Error11BackoffTest`, `EchoDiagnosticsTest` |
| **R11** | **Always-active live call + 60s auto-standby**: full-duplex VAD + barge-in; after 60s of silence enters `STANDBY` keeping only tiny wake-phrase detector; wakes on `"<Name>, wake up"` / `"<Name> jaag jao"`. | `F-116`–`F-120` | Module 2 | `StandbyCountdownAndWakePhraseTest`, `VoiceActivityDetectorPreRollTest` |
| **R12** | **Custom assistant name**: user can change name by voice (`"aaj se tumhara naam Jarvis hai"`) or UI; persisted in Room/Prefs and used as wake phrase & header title. | `F-121`–`F-123` | Module 2, Module 9 | `CustomAssistantRenameAndWakeTest` |
| **R13** | **Permanent memory & teaching**: Room DB + Android Keystore `AES/GCM/NoPadding` encryption for facts, aliases, routines, STT corrections, preferences, consented Wi-Fi passwords, and encrypted backup export/import. | `F-124`–`F-129` | Module 9 | `RoomAndKeystoreMemoryPersistenceTest`, `EncryptedBackupRoundTripTest` |
| **R14** | **Mood awareness & adaptation**: 9 smoothed mood states (`CALM`, `HAPPY`, `EXCITED`, `TIRED`, `SAD`, `STRESSED_HURRIED`, `ANGRY_FRUSTRATED`, `CONFUSED`, `NEUTRAL`) from words + prosody + repeated-command context, plus Self-Harm Helpline Guardrail (`1166`/`1122` PK, `988`/`112` global). | `F-130`–`F-133` | Module 10 | `NineStateMoodAnalyzerAndHelplineTest` |
| **R15** | **Movie-style (Jarvis) personality & modern Material 3 UI**: animated Orb with standby ring, mood tint, RTL Nastaliq + Latin mixed transcript, verified action cards, floating mini-orb overlay, and 5 tabs (`Home, History, Routines, Memory, Settings`). | `F-134`–`F-136` | Module 12 | `ComposeUiNavigationAndTagsTest` |
| **R16** | **First-run Permission Wizard & Diagnostics**: step-by-step bilingual onboarding for all permissions (`ROLE_ASSISTANT`, Mic, Contacts, Phone, SMS, Accessibility + Restricted Settings guide, Battery, Auto-Reset off, Shizuku) + Setup Health & Diagnostics screen. | `F-137`–`F-138` | Module 1 | `PermissionWizardAndSetupHealthTest` |
| **R17** | **Works on ANY Android 12–17 (`API 31–37`) phone**: `CapabilityProbe` classifies device into `Tier A / B / C / D`, detects OEM skin (Samsung, Xiaomi, Oppo, Realme, Vivo, Infinix, Tecno, Pixel, Motorola, OnePlus, Huawei), Dual-SIM, and Android 17 `AdvancedProtectionManager`. | `F-139` | Module 1, Module 6 | `CapabilityProbeTierClassifierTest`, `Android12To17CompatTest` |
| **R18** | **Signed Release Build, R8/ProGuard & Real-Phone Install Guide**: zero placeholders, R8 keep rules for Accessibility/VoiceInteraction/LiteRT/Room, and complete ADB + sideload + Developer Verification guide. | `F-140` | Module 13 | `compile_applet`, `gradle :app:testDebugUnitTest`, Release R8 config check |

---

## 2. Detailed Feature Catalog (`F-001`–`F-140`) with Minimum API Levels

| Feature ID | Req ID | Feature Description | Min API | Primary Class / File |
| :--- | :--- | :--- | :---: | :--- |
| **F-001** | R1, R17 | Enforce `minSdk = 31` (Android 12) and `Android12To17Compat` version branching (`API 31..37`). | 31 | `app/build.gradle.kts`, `Android12To17Compat.kt` |
| **F-002** | R1 | Request `RoleManager.ROLE_ASSISTANT` so `OmniVoiceInteractionService` can start mic FGS from background/reboot. | 31 | `OmniVoiceInteractionService.kt`, `PermissionsSetupScreen.kt` |
| **F-003** | R1 | Declare `RecognitionService` alongside `VoiceInteractionService` so Android Settings lists OmniVoice as a valid Digital Assistant. | 31 | `OmniRecognitionService.kt`, `AndroidManifest.xml` |
| **F-004** | R1 | Never start `FOREGROUND_SERVICE_TYPE_MICROPHONE` directly from `BOOT_COMPLETED` on Android 12–17; post a 1-tap "Wake OmniVoice" notification if not holding `ROLE_ASSISTANT`. | 31 / 34 | `BootCompletedReceiver.kt`, `WakeWordForegroundService.kt` |
| **F-005** | R1, R10 | Android 17 (`API 37`) Background-Audio Hardening detector: detect `AUDIOFOCUS_REQUEST_FAILED` or unchanged volume/ringer and alert user. | 31 / 37 | `AudioOutputManager.kt`, `Android12To17Compat.kt` |
| **F-006** | R1, R10 | Microphone privacy toggle detector (Android 12+): detect consecutive all-zero PCM frames when hardware mic mute switch is off. | 31 | `VoiceActivityDetector.kt`, `DiagnosticsScreen.kt` |
| **F-007** | R1, R4 | Android 17 `AdvancedProtectionManager` check: do NOT falsely declare `android:isAccessibilityTool`; detect when Advanced Protection disables Accessibility and fall back to L1/L3 ladder. | 31 / 37 | `accessibility_service_config.xml`, `Android12To17Compat.kt` |
| **F-008** | R4 | Detect `AccessibilityNodeInfo.isAccessibilityDataSensitive()` (API 34+) and `FLAG_SECURE` windows and report honestly when blocked. | 34 | `OmniAccessibilityService.kt` |
| **F-009** | R16 | Guide user to disable Android Permission Auto-Reset (`Intent.ACTION_AUTO_REVOKE_PERMISSIONS`, API 31+) via `PackageManager.isAutoRevokeWhitelisted()`. | 31 | `PermissionsSetupScreen.kt`, `Android12To17Compat.kt` |
| **F-010** | R17 | `CapabilityProbe`: detect SDK/patch, OEM skin, RAM, free storage, ABI (`arm64-v8a`), Play Services, AICore, STT/TTS voices (`ur-PK`, `en`), Shizuku, Dual-SIM, and assign `Tier A / B / C / D`. | 31 | `CapabilityProbe.kt`, `DiagnosticsScreen.kt` |
| **F-011** | R3, R17 | Dual-SIM awareness (`SubscriptionManager`, `TelecomManager` phone account selection for `"SIM 1"` / `"SIM 2"` calls & SMS). | 31 | `DualSimManager.kt`, `ActionExecutor.kt` |
| **F-012** | R18 | Signed Release build configuration + `proguard-rules.pro` keeping `AccessibilityService`, `VoiceInteractionService`, `RecognitionService`, Room entities, and serialization models. | 31 | `app/build.gradle.kts`, `app/proguard-rules.pro` |
