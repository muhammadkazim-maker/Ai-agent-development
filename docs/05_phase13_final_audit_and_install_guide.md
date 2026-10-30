# Phase 13 Final Audit & Real-Phone Install Guide (`F-001`–`F-120`)

## 1. Complete Feature Audit Table (`F-001`–`F-120`)

| Feature ID | Requirement Summary | Implemented In | Verification Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| **F-001** | Single-Activity, single-module (`:app`), Kotlin + Jetpack Compose + Material 3 (`minSdk=26`, `targetSdk=36`) | `app/build.gradle.kts`, `MainActivity.kt` | `compile_applet` | **DONE** |
| **F-002** | Zero NDK/C++ native build dependencies | `app/build.gradle.kts` | Gradle dependency check | **DONE** |
| **F-003** | `network_security_config.xml` enforcing HTTPS with localhost loopback exception | `res/xml/network_security_config.xml`, `AndroidManifest.xml` | Manifest & XML inspection | **DONE** |
| **F-004..F-012** | Never-Fake-Success `ActionResult` (`Success`, `Failed`, `NeedsPermission`, `NeedsConfirmation`) & real post-action verification | `ActionResult.kt`, `ActionVerifier.kt`, `ActionExecutor.kt` | `ExampleRobolectricTest` | **DONE** |
| **F-013, F-039, F-040** | Single audio output path (`AudioOutputManager`), monotonic `turnId` deduplication, Cloud PCM vs Local TTS mutual exclusion | `AudioOutputManager.kt` | `Module 13 Quality Gate` Robolectric test | **DONE** |
| **F-014** | Confirm risky actions (calls, SMS, WhatsApp, payments, deletions, unknown Wi-Fi) before executing | `ActionExecutor.kt`, `SecurityVaultManager.kt` | `ExampleRobolectricTest` | **DONE** |
| **F-015, F-102** | Prompt-Injection Firewall wrapping screen/notification/web text in `<untrusted_data>` tags | `ConnectivityAndSpellingEngine.kt` (`PromptInjectionFirewall`), `system_prompt_cloud.txt` | `Module 13 Quality Gate` Robolectric test | **DONE** |
| **F-016..F-025** | 24/7 Foreground Wake-Word Service, `AssistantAudioStateMachine` single microphone owner, Error 11 exponential backoff, VAD (`VoiceActivityDetector`), VoiceInteractionService | `WakeWordForegroundService.kt`, `AssistantAudioStateMachine.kt`, `VoiceActivityDetector.kt`, `OmniVoiceInteractionService.kt` | `ExampleRobolectricTest` | **DONE** |
| **F-026..F-038** | 3-Way Language Mastery (English, Urdu Nastaliq `اردو`, Roman Urdu, Mixed), spoken fractional times (`saarhe`, `dhai`, `dedh`, `paune`, `sau`), cross-script contact matching, on-device spelling recognizer | `LanguageProcessor.kt`, `ConnectivityAndSpellingEngine.kt`, `system_prompt_cloud.txt`, `system_prompt_local.txt`, `BilingualBenchmarkRunner.kt` | 200+ bilingual benchmark suite + Robolectric tests | **DONE** |
| **F-041, F-042** | Hardware `AcousticEchoCanceler`/`NoiseSuppressor`/`AutomaticGainControl`, instant barge-in (< 150 ms), interactive Acoustic Echo Test | `AudioOutputManager.kt`, `DiagnosticsScreen.kt` | `DiagnosticsScreen` + Robolectric test | **DONE** |
| **F-043, F-044** | `SpeechNormaliser` (`displayText` vs `speechText`, phone digit spacing, code-mixed `LanguageRun` splitter) & 1-tap Offline TTS installer | `AudioOutputManager.kt` (`SpeechNormaliser`), `DiagnosticsScreen.kt` | `Module 13 Quality Gate` Robolectric test | **DONE** |
| **F-045..F-054** | Hybrid Offline + Online Brain, `OfflineCommandEngine` (< 50 ms), AICore Gemini Nano + LiteRT-LM recommender, Privacy Mode (Offline-Only), Prerequisite Resolver (`Steps A-D`) | `OfflineCommandEngine.kt`, `ConnectivityAndSpellingEngine.kt`, `OmniAutonomousCoreEngine.kt`, `SettingsHubScreen.kt` | `ExampleRobolectricTest` | **DONE** |
| **F-055..F-068** | 4-Layer Control Ladder (`L1 -> L2 -> L3 -> L4`), `OmniAccessibilityService`, OEM Selector Packs (`oem_selectors.json`), sensitive clipboard password paste + clear, Android 13+ Restricted Settings helper | `OmniAccessibilityService.kt`, `ConnectivityAndSpellingEngine.kt`, `assets/oem_selectors.json`, `PermissionsSetupScreen.kt` | `Module 13 Quality Gate` Robolectric test | **DONE** |
| **F-069..F-086** | 35+ Verified Device Tools (Calls, SMS, WhatsApp, Social, Alarms, Timers, Torch, Volume, Media, Navigation, Screen OCR, Find Phone, Routines, Conditional Rules) | `ActionExecutor.kt`, `OmniLiveSessionEngine.kt`, `JarvisHandsFreeEngine.kt`, `RoutineDao.kt` | `ExampleRobolectricTest` | **DONE** |
| **F-087..F-092** | Permanent Self-Learning Memory (`Room` + Android Keystore `AES/GCM/NoPadding`), Custom Wake-Word/Rename, Personal Dictionary, Encrypted Backup Export/Import | `OmniVoiceDatabase.kt`, `MoodAndMemoryEngine.kt`, `VocabularyScreen.kt`, `SettingsHubScreen.kt` | `Module 13 Quality Gate` Robolectric test | **DONE** |
| **F-093..F-097** | 9-State Affective Mood Engine (EMA-smoothed lexicon + prosody) + Self-Harm Distress Helpline Guardrail (`1166`/`1122` PK, `988`/`112` global) | `MoodAndMemoryEngine.kt` (`MoodAnalyzerEngine`), `SettingsHubScreen.kt` | `Module 13 Quality Gate` Robolectric test | **DONE** |
| **F-098..F-104** | Spoken Passphrase & Voice Biometrics (`SecurityVaultManager`), Lock-screen risk matrix, `OmniDeviceAdminReceiver`, masked secrets (`********`) | `SecurityVaultManager.kt`, `OmniDeviceAdminReceiver.kt`, `SecuritySetupScreen.kt` | `ExampleRobolectricTest` | **DONE** |
| **F-105..F-120** | All-Permissions Onboarding Wizard, Live Hardware Diagnostics Screen, 5-Tab Jetpack Compose UI (`Home`, `History`, `Routines`, `Memory`, `Settings`), Automated Tests | `PermissionsSetupScreen.kt`, `DiagnosticsScreen.kt`, `MainActivity.kt`, `ExampleRobolectricTest.kt` | `gradle :app:testDebugUnitTest` | **DONE** |

---

## 2. Non-Root Android Restrictions & Honest Workaround Matrix

| Android OS Restriction | Affected SDK / OEM | OmniVoice Honest Workaround (`L1 -> L2 -> L3 -> L4`) |
| :--- | :--- | :--- |
| **`WifiManager.setWifiEnabled()` deprecated/blocked** | Android 10+ (API 29+) | **L3** (`Settings.Global.WIFI_ON` if `WRITE_SECURE_SETTINGS` granted via ADB) -> **L2** (`OmniAccessibilityService` quick-settings/panel toggle) -> **L4** (`Settings.Panel.ACTION_WIFI` 1-tap sheet). |
| **Mobile Data toggle blocked for third-party apps** | Android 5.0+ (API 21+) | **L3** (`svc data enable` via Shizuku/ADB) -> **L2** (Accessibility Quick Settings tile click) -> **L4** (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`). |
| **Connecting to an arbitrary Wi-Fi SSID with password** | Android 10+ (API 29+) | **L1** (`WifiNetworkSpecifier` / `ACTION_WIFI_ADD_NETWORKS` on API 30+) -> **L2** (`OmniAccessibilityService` opens Wi-Fi settings, taps SSID, pastes password with `EXTRA_IS_SENSITIVE`, taps Connect, clears clipboard, verifies `NET_CAPABILITY_VALIDATED`). |
| **Android 13+ Sideloaded APK "Restricted Settings" block** | Android 13+ (API 33+) | Dedicated **1-Tap Unlock Card** in `PermissionsSetupScreen` opens App Info (`ACTION_APPLICATION_DETAILS_SETTINGS`) with visual instructions (`⋮ -> Allow restricted settings`), plus `android:isAccessibilityTool="true"` in `accessibility_service_config.xml`. |
| **Lock-Screen Keyguard Dismissal** | All non-root Android | Uses `Activity.setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and `KeyguardManager.requestDismissKeyguard()`. Never stores raw lock-screen PIN in plaintext. |

---

## 3. Real-Phone Installation & Setup Guide

1. **Build & Install the APK:**
   - In Android Studio, open the project and click **Run ('app')** on your connected Android phone (API 26–36), or run `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.
2. **Configure Gemini API Key:**
   - Add `GEMINI_API_KEY=your_key_here` in AI Studio Secrets / `.env` (or in the in-app Settings Vault).
3. **Complete the 1-Time Permissions Setup (`Settings -> Permissions`):**
   - Tap **"Request All App Permissions"** to grant Microphone, Phone, SMS, Contacts, and Notifications.
   - If sideloaded on Android 13+, tap **"Open App Info (⋮ -> Allow Restricted Settings)"**, tap the top-right `⋮` menu, and select **Allow restricted settings**.
   - Enable **OmniVoice Accessibility Service**, **Notification Listener Access**, and **Battery Optimization Exemption**.
4. **Optional L3 Zero-Touch Power-User Grant (One-Time via PC ADB):**
   ```bash
   adb shell pm grant com.aistudio.omnivoice.jarvis android.permission.WRITE_SECURE_SETTINGS
   ```
5. **Run Hardware & Echo Diagnostics (`Settings -> Diagnostics`):**
   - Tap **"Run Echo Test"** and **"Test Voice TTS"** to verify hardware AEC, microphone RMS levels, and bilingual Urdu/English speech synthesis.
