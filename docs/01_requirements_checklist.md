# Phase 0 (a): OmniVoice Master Requirements Checklist (`F-001` – `F-120`)

Every requirement and sentence in the master engineering brief is mapped below to a unique Feature ID, its target build module (Modules 1–13), minimum Android API level, and verification mechanism.

---

## 0. Project & Builder Constraints (`F-001` – `F-008`)

| ID | Requirement Summary | Min API | Target Module | Verification Method |
|---|---|---|---|---|
| **F-001** | Native Android app in Kotlin, Jetpack Compose, Material 3 (`minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`). Never a WebView, PWA, React, or Flutter app. | API 26 | Module 1 | Gradle config + APK inspection |
| **F-002** | Single-Activity (`MainActivity`), single-module (`:app`) architecture with Compose Navigation. | API 26 | Module 1 | `AndroidManifest.xml` + Navigation graph |
| **F-003** | Compose-only UI (zero XML layouts); system config XMLs in `res/xml/` (`accessibility_service_config.xml`, `device_admin_receiver.xml`, `voice_interaction_service.xml`, `network_security_config.xml`) are created as required by Android OS. | API 26 | Module 1 | `res/` directory audit |
| **F-004** | No NDK / custom C++ compilation; use only prebuilt Maven / Google Maven libraries pinned in `gradle/libs.versions.toml`. | API 26 | Module 1 | `libs.versions.toml` + `build.gradle.kts` |
| **F-005** | Client-side only architecture (no custom backend server required). | API 26 | Module 1 | Network call audit |
| **F-006** | Customisable assistant name (default `"Omni"`), changeable anytime by voice or UI, persisted permanently. | API 26 | Module 1, 2, 9 | Room + SharedPreferences persistence test |
| **F-007** | No placeholders, no `TODO`s, no truncated files, and honest disclosure of any non-root Android OS limitation with its exact workaround. | API 26 | All Modules | Static grep check + Phase 13 audit |
| **F-008** | Pinned Version Catalog (`gradle/libs.versions.toml`) with explicit minimum API level annotations on all APIs above API 26. | API 26 | Module 1 | Gradle build + Lint check |

---

## Core Principles (`P1` – `P7` -> `F-009` – `F-015`)

| ID | Principle | Requirement Detail | Min API | Target Module |
|---|---|---|---|---|
| **F-009** | **P1: Offline-First** | Calls, SMS, contacts, apps, alarms, timers, reminders, settings, flashlight, volume, brightness, camera, screenshots, files, notes, memory, UI control, and on-device conversation work in Airplane Mode (Wi-Fi and mobile data off). | API 26 | Modules 2–9 |
| **F-010** | **P2: Universal Agent Loop** | Fast native tool when available; otherwise generic `observe -> decide -> act -> verify` loop over the live screen (`AccessibilityNodeInfo` tree + screenshot + global actions). Fallback chain: `Tool -> UI Automation -> 1 Clarifying Question -> Exact Android Restriction + One-Time Setup Guide`. | API 26 | Modules 5, 6, 8 |
| **F-011** | **P3: Never Fake Success** | Typed `sealed class ActionResult`: `Success(detail)`, `Failed(reason)`, `NeedsPermission(name)`, `NeedsConfirmation(message)`, `InProgress(stage)`. Every action is verified against real OS state before reporting to the LLM or user. | API 26 | Module 5 |
| **F-012** | **P4: Zero Touch** | End-to-end voice execution: tapping, scrolling, typing text and passwords, toggling settings, connecting to Wi-Fi without manual screen touches whenever Android permits. | API 26 | Modules 5, 6, 7 |
| **F-013** | **P5: One Voice, One Audio Path** | Strictly one microphone owner and one speaker output owner (`AudioOutputManager`) at any instant; zero overlapping TTS + Live audio or duplicate collectors. | API 26 | Module 2, 3 |
| **F-014** | **P6: Remember Forever** | Everything taught by the user (facts, nicknames, routines, corrections, preferences) is written immediately to Room disk storage and confirmed only after commit. | API 26 | Module 9 |
| **F-015** | **P7: Safety & Prompt-Injection Defence** | Confirm risky actions; treat screen text, notifications, messages, web pages, and files strictly as untrusted `DATA` (tagged `<untrusted_data>`); authorize sensitive actions only via verified user voice/passphrase; maintain full audit log. | API 26 | Modules 8, 11 |

---

## Section 1: Always-Active Live Conversation, Standby, Name & Personality (`F-016` – `F-024`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-016** | Default continuous full-duplex "live call" mode on launch with natural VAD turn-taking and barge-in interruption, plus optional manual UI toggle. | API 26 | Module 2 |
| **F-017** | Two explicit power states: `ACTIVE` (live conversation) and `STANDBY` (sleeping). | API 26 | Module 2 |
| **F-018** | Configurable 60-second inactivity auto-standby timer: if no user speech, no running task, and no pending question for 60s, speak `"Standby mode mein ja raha hoon. Jagane ke liye '<Name>, wake up' kahein."`, stop cloud streaming/STT/LLM, and keep only the lightweight on-device wake-phrase detector running. | API 26 | Module 2 |
| **F-019** | Activity reset rule: any user speech, running task, pending question, phone call handling, or assistant speech pauses/resets the 60s countdown. | API 26 | Module 2 |
| **F-020** | Standby wake-phrase filter: ignore all speech in `STANDBY` except `"<Name>, wake up"` and Urdu/Roman-Urdu variants (`"<Name> jaag jao"`, `"<Name> uth jao"`, `"<Name> utho"`). Play wake chime + greeting and transition to `ACTIVE`. | API 26 | Module 2 |
| **F-021** | Immediate voice sleep command: `"standby"`, `"so jao"`, `"sleep mode"` transitions immediately to `STANDBY`. | API 26 | Module 2 |
| **F-022** | Offline on-device wake-phrase engine supporting custom assistant names without NDK compilation (VAD-gated acoustic + restricted-vocabulary phonetic matcher, with optional Picovoice Porcupine / ONNX openWakeWord support). | API 26 | Module 2 |
| **F-023** | Permanent assistant renaming by voice (`"aaj se tumhara naam Jarvis hai"`) or UI settings; updates header, greetings, and wake phrase immediately. | API 26 | Module 2, 9 |
| **F-024** | Calm, witty, movie-style personality addressing user by chosen title (`"boss"`, `"ustad"`, etc.), concise by default, detailed on request, proactive on low battery / missed calls / alarms, and full conversational Q&A offline & online. | API 26 | Module 4, 8, 10 |

---

## Section 2: Urdu + English + Roman Urdu + Mixed NLP (`F-025` – `F-032`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-025** | Automatic per-utterance language detection (`ENGLISH`, `URDU_NASTALIQ`, `ROMAN_URDU`, `MIXED`) and strict reply-language mirroring (never switch language unprompted). | API 26 | Module 4 |
| **F-026** | Pre-model normalisation pipeline: STT error correction, spoken numbers & times (`"do baje"`, `"saarhe teen"`, `"dhai baje"`, `"paune paanch"`, `"paanch sau"`, `"پانچ بجے"`), relative dates (`"kal"`, `"parson"`, `"agle hafte"`), day parts (`"subah"`, `"dopahar"`, `"shaam"`, `"raat"`). | API 26 | Module 4 |
| **F-027** | Roman Urdu spelling canonicalisation (`kya/kia`, `nahi/nahin`, `karo/kro`, `krna/karna`), politeness detection (`aap/tum/tu`), Urdu digits (`۰-۹` -> `0-9`), and SIM-aware phone number normalisation (`03xx` -> `+923xx`). | API 26 | Module 4 |
| **F-028** | Cross-script phonetic & fuzzy matching for contact and app names (`"علی" == "Ali" == "Aly"`, `"واٹس ایپ" == "whatsapp"`), asking exactly ONE short disambiguation question when ambiguous. | API 26 | Module 4, 5 |
| **F-029** | `assets/vocabulary.json` structured dataset (grouped across calls, messages, time, apps, settings, Wi-Fi/internet, family, food, travel, health, religion, daily life, numbers, action verbs; expandable in batches to 3,000+ entries). | API 26 | Module 4 |
| **F-030** | `assets/commands_dataset.json` structured dataset (all 4 language styles mapped to tool-call JSON across offline/online tasks, Wi-Fi flow, and multi-step chains; expandable in batches to 800+ entries) + `assets/stt_corrections.json`. | API 26 | Module 4 |
| **F-031** | Few-shot retrieval engine (keyword + TF-IDF/cosine similarity) injecting top matching examples from `commands_dataset.json` into each LLM prompt. | API 26 | Module 4, 8 |
| **F-032** | Dedicated cloud (`assets/system_prompt_cloud.txt`) and compact on-device (`assets/system_prompt_local.txt`) system prompt files enforcing language mirroring, tone, and Never-Fake-Success. | API 26 | Module 4 |

---

## Section 3: Audio Pipeline & Foreground Service (`F-033` – `F-046`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-033** | Root-cause fix for Bug #1 (`SpeechRecognizer` Error 11 `ERROR_SERVER_DISCONNECTED`), Bug #2 (unclear voice), and Bug #3 (second background voice / echo). | API 26 | Module 2, 3 |
| **F-034** | Single-owner `AudioRecord` capture (16 kHz mono PCM16, `MediaRecorder.AudioSource.VOICE_COMMUNICATION` vs `VOICE_RECOGNITION` benchmark) with hardware `AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl` attachment & diagnostic logging. | API 26 | Module 2 |
| **F-035** | Deterministic single-owner microphone state machine: `STANDBY -> LISTENING -> USER_SPEAKING -> THINKING_ACTING -> SPEAKING -> LISTENING`. | API 26 | Module 2 |
| **F-036** | On-device Voice Activity Detection (VAD) with pre-roll ring buffer (300ms) for end-of-speech and barge-in detection; never stream continuous silence to the cloud. | API 26 | Module 2 |
| **F-037** | Multi-tier STT fallback chain with exponential backoff, main-thread recognizer lifecycle, human-readable error names, and per-language benchmark memory: (1) Online Gemini Audio/Live, (2) Offline Android 13+ `createOnDeviceSpeechRecognizer` (API 33+) / prebuilt offline Whisper/Vosk runtime, (3) Classic `SpeechRecognizer` (`ur-PK`, `en-US`, `en-IN`). | API 26 (API 33 for On-Device SR) | Module 3 |
| **F-038** | Strict local-only STT isolation for secrets: passwords, PINs, passphrases, and Wi-Fi passwords pause cloud streaming and use on-device STT only, with zero logging. | API 26 | Module 3, 7, 11 |
| **F-039** | Single `AudioOutputManager` singleton owning one `AudioTrack` / `TextToSpeech` instance (`USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`), turn-ID playback queue, instant barge-in flush, and turn-ID deduplication so no turn ever plays twice. | API 26 | Module 2, 3 |
| **F-040** | Single response modality rule: `outputAudioTranscription` is used strictly for UI subtitles and never fed into local TTS while Gemini Live PCM audio is playing; handle session resumption and `GoAway` frames without replaying stale audio. | API 26 | Module 3 |
| **F-041** | Acoustic Echo Control + half-duplex barge-in threshold fallback when hardware AEC is unavailable, plus interactive **Echo Test** in Diagnostics (plays reference tone, measures mic pickup, reports pass/fail). | API 26 | Module 2, 12 |
| **F-042** | AudioFocus management (`AudioFocusRequest` on API 26+), speaker/earpiece/Bluetooth SCO & A2DP routing, and post-call audio mode restoration. | API 26 | Module 2 |
| **F-043** | 24 kHz cloud PCM jitter buffer + `SpeechNormaliser` producing separate `displayText` and `speechText` (stripping markdown/URLs/emojis, expanding numbers/dates/phones, Roman-Urdu-to-Urdu script conversion for Urdu TTS, and seamless code-mixed language-run splitting). | API 26 | Module 3 |
| **F-044** | Voice settings & offline TTS manager: voice picker with Preview, speed, pitch, volume, mood-adaptive prosody, and `TextToSpeech.getVoices()` inspection with guided installer (`TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA`) for missing offline Urdu (`ur-PK`) and English voices. | API 26 | Module 3 |
| **F-045** | `WakeWordForegroundService` (`foregroundServiceType="microphone"` on API 29+/34+) with `Sleep / Wake / Stop` notification actions, `START_STICKY`, `BootCompletedReceiver`, `WorkManager` watchdog, scoped `WakeLock`, battery optimization exemption, and OEM autostart guides (Xiaomi, Oppo, Realme, Vivo, Infinix, Tecno, Samsung, Huawei). | API 26 (API 34 FGS rules) | Module 2 |
| **F-046** | Android 14/15 background microphone FGS start restriction handling (fallback to high-priority full-screen/one-tap activation notification on boot), automatic pause during cellular/VoIP phone calls (`TelephonyCallback` / `PhoneStateListener`), and `VoiceInteractionService` registration for lock-screen trigger. | API 26 (API 31 `TelephonyCallback`) | Module 2 |

---

## Section 4: Hybrid Brain — Offline + Online (`F-047` – `F-054`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-047** | Fast deterministic local command parser (`DeterministicCommandParser`) handling 300+ device commands in English, Urdu, Roman Urdu, and mixed speech in <50ms offline. | API 26 | Module 4, 8 |
| **F-048** | On-device LLM integration: Gemini Nano via AICore / ML Kit GenAI where hardware-supported, plus Gemma-class `.litertlm` / GGUF runtime via LiteRT-LM Kotlin API with RAM/ABI model selector and non-bundled **Model Download & Storage Import Manager** (progress, checksum, resume, local file picker). | API 26 | Module 8 |
| **F-049** | Cloud Gemini engine via Firebase AI Logic with App Check (`firebase-ai`), supporting Live real-time voice, Flash fast tool calling, Pro thinking mode, and Google Search grounding with retry/backoff. | API 26 | Module 8 |
| **F-050** | Smart Hybrid Router: device hardware/app actions execute locally first; live web knowledge & complex reasoning route to cloud when validated internet is active; automatic offline fallback with transparent user notice. | API 26 | Module 8 |
| **F-051** | Strict **Privacy Mode** toggle (100% offline execution; blocks all cloud calls) and permanent secret redaction filter so PINs, passphrases, and Wi-Fi passwords never leave the device. | API 26 | Module 8, 11 |
| **F-052** | Typed `ToolRegistry` with JSON argument schema, `requiresInternet`, `riskLevel` (`LOW`, `MEDIUM`, `HIGH`), `requiresUnlock`, and deterministic post-condition `ActionVerifier`. | API 26 | Module 5, 8 |
| **F-053** | Code-level tool call validator rejecting hallucinated tool names, malformed arguments, or non-existent contacts/apps before execution and triggering re-planning. | API 26 | Module 8 |
| **F-054** | Multi-step & conditional `AgentLoopEngine` (max steps, per-step timeout, error recovery, and persistent event triggers for `"Agar Ali ka call aaye..."`) producing a single verified spoken summary. | API 26 | Module 8 |

---

## Section 5: Execution Engine & L1–L4 Control Ladder (`F-055` – `F-061`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-055** | 4-Tier Control Ladder with automatic verification & fallback: `L1` Official Android SDK / Intent -> `L2` `OmniAccessibilityService` UI Automation -> `L3` Privileged Shizuku / ADB `WRITE_SECURE_SETTINGS` shell commands -> `L4` Optional Root shell. | API 26 | Module 6 |
| **F-056** | `OmniAccessibilityService` node finder supporting fuzzy multi-language matching across `text`, `contentDescription`, `viewIdResourceName`, and `className`, plus `waitForWindowIdle`, retries, and automatic `ACTION_SCROLL_FORWARD` / `dispatchGesture` scroll-to-find. | API 26 | Module 6 |
| **F-057** | Resilient text & password injection via `ACTION_SET_TEXT` with sensitive-marked `ClipboardManager` (`ClipDescription.EXTRA_IS_SENSITIVE` on API 33+) paste fallback and immediate clipboard wipe. | API 26 (API 33 sensitive flag) | Module 6 |
| **F-058** | Full gesture & global action suite: `ACTION_CLICK`, `ACTION_LONG_CLICK`, `dispatchGesture` (tap/swipe), `takeScreenshot` (API 30+), global actions (`HOME`, `BACK`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `LOCK_SCREEN` API 28+, `POWER_DIALOG`), foreground package tracker, and auto-popup handler (`"Allow"`, `"While using the app"`, `"OK"`). | API 26 (API 28/30) | Module 6 |
| **F-059** | `FLAG_SECURE` / banking window detector that halts automation and explains honestly when Android blocks screen reading or input on protected financial screens. | API 26 | Module 6 |
| **F-060** | Updatable JSON OEM Selector Packs (`assets/oem_selectors.json` for Samsung OneUI, Xiaomi HyperOS/MIUI, Oppo ColorOS, Realme, Vivo Funtouch, Infinix/Tecno HiOS/XOS, Pixel, Motorola) + LLM self-healing UI tree analyzer when static selectors miss. | API 26 | Module 6 |
| **F-061** | `android:isAccessibilityTool="true"` declaration (API 31+), Android 13+ (API 33+) *"Allow restricted settings"* visual step-by-step guide, and service death watchdog with automatic re-enable when `WRITE_SECURE_SETTINGS` is granted. | API 26 (API 31/33) | Module 1, 6 |

---

## Section 6: Connectivity Assistant — 9-Step Zero-Touch Wi-Fi & Data Flow (`F-062` – `F-070`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-062** | **Step 1 (`NetworkMonitor`):** Real-time `ConnectivityManager.NetworkCallback` tracking Wi-Fi radio state, connected SSID, mobile data state, airplane mode, `NET_CAPABILITY_VALIDATED`, and `NET_CAPABILITY_CAPTIVE_PORTAL`. | API 26 | Module 7 |
| **F-063** | **Step 2 (Pending Task Queue):** When an online tool is requested with no validated internet, store the command in `PendingTask` and ask in the user's language: *"Aapka mobile abhi internet se connected nahi hai. Kya main WiFi ya mobile data on kar doon?"* | API 26 | Module 7 |
| **F-064** | **Step 3 (Radio Enable via Control Ladder):** On *"wifi on kar do"* or *"data on karo"*, enable radio using `L3` (`svc wifi enable` / `svc data enable`) or `L2` (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY` on API 29+ + Accessibility toggle click) or `L1` (`WifiManager.setWifiEnabled(true)` on API 26–28), and verify radio state. | API 26 (API 29 Panel) | Module 7 |
| **F-065** | **Step 4 (Network Discovery & Ranking):** Scan and list available SSIDs via `WifiManager.getScanResults()` and/or Accessibility scraping of the Wi-Fi settings list, sorted by signal strength (RSSI), labelled `saved`, `password chahiye` (secured), or `open`. | API 26 | Module 7 |
| **F-066** | **Step 5 (Network Selection):** Match spoken network name; connect immediately if saved/open, or prompt for password if secured. | API 26 | Module 7 |
| **F-067** | **Step 6 (On-Device Spelling Password Capture & Auto-Type):** Capture password using **on-device STT only** with character-by-character spelling parser (`"capital A"`, `"chhota b"`, `"number 5"`, `"underscore"`, `"dash"`, `"dot"`, Urdu letter names, NATO phonetics), read back for confirmation (*"haan"* / *"theek hai"*), type into password dialog via `ACTION_SET_TEXT` / `WifiNetworkSuggestion` / `cmd wifi connect-network`, tap Connect, and detect wrong-password errors (up to 3 retries). Zero logging or plain-text display. | API 26 (API 29 `WifiNetworkSpecifier`) | Module 7 |
| **F-068** | **Step 7 (Validation & Captive Portal Check):** Verify target SSID matches AND `NET_CAPABILITY_VALIDATED` is true; if connected without internet (captive portal / zero data balance), inform the user and offer to switch to Mobile Data. | API 26 | Module 7 |
| **F-069** | **Step 8 (Optional Keystore Encrypted Save):** Offer to save the Wi-Fi password in Android Keystore (`AES/GCM/NoPadding`) only with explicit user consent. | API 26 | Module 7, 9 |
| **F-070** | **Step 9 (Auto-Resume Pending Task):** Automatically execute the queued `PendingTask` once internet is validated and speak the verified result; support identical recovery flows for Mobile Data, Airplane Mode, and Hotspot. | API 26 | Module 7 |

---

## Section 7: Contacts, WhatsApp & Installed Apps (`F-071` – `F-076`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-071** | `ContactRepository` loading 100% of contacts across `ContactsContract.Contacts`, `RawContacts`, and `Data` (Phone, SIM, Google, WhatsApp `com.whatsapp`, WhatsApp Business `com.whatsapp.w4b`, Telegram) with E.164/country normalisation, deduplication, and live `ContentObserver` sync. | API 26 | Module 5 |
| **F-072** | Nickname & relationship resolution (`Ammi`, `Abbu`, `Bhai`, `Baji`, `Sir`, etc.), cross-script fuzzy matching, single-question disambiguation when multiple contacts match, and voice contact creation/editing (`ContactsContract` insert/update). | API 26 | Module 5 |
| **F-073** | WhatsApp direct chat, voice call, and video call via `ContactsContract.Data` MIME rows (`vnd.android.cursor.item/vnd.com.whatsapp.profile`, `vnd.android.cursor.item/vnd.com.whatsapp.voip.call`, `vnd.android.cursor.item/vnd.com.whatsapp.video.call`) with fallback to `whatsapp://send?phone=...` and `OmniAccessibilityService` auto-type + Send click + post-send verification (Send button gone / message bubble present). | API 26 | Module 5, 6 |
| **F-074** | Incoming WhatsApp / SMS / Telegram message reader & voice reply via `OmniNotificationListenerService` (`Notification.Action` `RemoteInput` direct reply without opening the app when available, or Accessibility fallback). | API 26 | Module 5 |
| **F-075** | Installed App Registry (`QUERY_ALL_PACKAGES` + `PackageManager.queryIntentActivities`) indexing every installed app with English, Urdu Nastaliq, and Roman Urdu aliases. | API 26 (API 30 `<queries>`) | Module 5 |
| **F-076** | Verified app launching & deep-link routing checking foreground package via `OmniAccessibilityService` / `UsageStatsManager` before returning `ActionResult.Success`. | API 26 | Module 5 |

---

## Section 8: Complete Verified Device Capabilities (`F-077` – `F-086`)

| ID | Capability Group | Specific Verified Actions | Min API | Target Module |
|---|---|---|---|---|
| **F-077** | **Telephony & Calls** | Place call (`ACTION_CALL`), answer incoming call (`TelecomManager.acceptRingingCall()` API 26+), reject/end call (`TelecomManager.endCall()` API 28+ / `InCallService` / Accessibility), toggle speakerphone & mute, read/search Call Log (`CallLog.Calls`). | API 26 (API 28 `endCall`) | Module 5 |
| **F-078** | **Messaging & Email** | Send/read SMS (`SmsManager` with `sentIntent` & `deliveryIntent` `PendingIntent` verification), WhatsApp, Telegram, and Email (`ACTION_SENDTO` + Accessibility send). | API 26 | Module 5 |
| **F-079** | **Time, Calendar & Productivity** | Read/create Calendar events (`CalendarContract.Events` + read-back verification), set/dismiss Alarms (`AlarmClock.ACTION_SET_ALARM` + `AlarmManager`), Timers (`AlarmClock.ACTION_SET_TIMER`), Reminders, and local voice Notes. | API 26 | Module 5 |
| **F-080** | **App & In-App UI Control** | Open, close, switch recent apps, tap buttons, scroll lists, fill forms, and dictate text into any focused input field across the OS. | API 26 | Module 5, 6 |
| **F-081** | **Radios & System Settings** | Wi-Fi, Bluetooth (enable/disable + pair/connect by device name), Mobile Data, Hotspot, Airplane Mode, Flashlight (`CameraManager.setTorchMode`), Volume (`AudioManager` streams), Brightness (`Settings.System.SCREEN_BRIGHTNESS`), DND (`NotificationManager.setInterruptionFilter`), Auto-Rotate, Battery Saver, Location. | API 26 | Module 5, 6, 7 |
| **F-082** | **Media & Camera** | Media playback control (`AudioManager.dispatchMediaKeyEvent` play/pause/next/previous), take photo/video (`CameraX` / `MediaStore` intent + Accessibility shutter), take screenshot (`AccessibilityService.takeScreenshot` API 30+), screen recording (`MediaProjectionManager`). | API 26 (API 30 screenshot) | Module 5, 6 |
| **F-083** | **Notifications** | Read active notifications aloud, reply inline via `RemoteInput`, dismiss single or all notifications (`NotificationListenerService.cancelNotification`). | API 26 | Module 5 |
| **F-084** | **Files, Gallery & Downloads** | Search, open, and share files/photos/downloads (`MediaStore` + `StorageAccessFramework`). | API 26 | Module 5 |
| **F-085** | **Navigation, Web, Translation & Find-My-Phone** | Google Maps turn-by-turn navigation (`google.navigation:q=...`), grounded web search & Q&A, bidirectional Urdu-English translation, and Find-My-Phone max-volume ring + flash strobe. | API 26 | Module 5, 8 |
| **F-086** | **Screen Reader & Custom Routines** | Read current screen aloud, answer *"what's on my screen?"* (with `<untrusted_data>` sandboxing), and execute multi-action user routines. | API 26 | Module 6, 8, 9 |

---

## Section 9: Permanent Memory & Teaching (`F-087` – `F-092`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-087** | Room database (`OmniVoiceDatabase`) with Android Keystore `AES/GCM/NoPadding` field encryption for sensitive entries, storing: personal/contact facts, contact/app/place aliases, structured voice routines (steps + conditions), STT/word corrections, user preferences, consented Wi-Fi credentials, conversation summaries, and opt-in mood summaries. | API 26 | Module 9 |
| **F-088** | Synchronous disk commit before spoken confirmation (*"Yaad kar liya: ..."* only after Room write succeeds), surviving app restarts, updates, and device reboots. | API 26 | Module 9 |
| **F-089** | Natural voice teaching commands: facts (*"yeh yaad rakho ki..."*), routines (*"seekho: jab main 'movie mode' kahun..."*), user title (*"aaj se mujhe boss kaha karo"*), STT corrections (*"yeh lafz ghalat samjha, sahi ye hai..."*), and forgetting (*"bhool jao..."* with confirmation). | API 26 | Module 9 |
| **F-090** | Dedicated **Memory Screen** in UI: view, search, edit, delete, encrypted JSON backup export/import, plus voice command *"show me what you learned"*. | API 26 | Module 9, 12 |
| **F-091** | Privacy-aware memory retrieval into every local and cloud prompt, plus on-device rolling summarization of long conversations into compact memory notes. | API 26 | Module 9 |
| **F-092** | Honest disclosure in UI & docs that on-device teaching uses persistent Room memory + RAG retrieval + few-shot injection (not weight gradient fine-tuning on the handset). | API 26 | Module 9 |

---

## Section 10: Mood Awareness & Affective Adaptation (`F-093` – `F-097`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-093** | Multi-signal per-turn mood estimator combining: (a) trilingual sentiment lexicon (Urdu / English / Roman Urdu / emojis) + LLM signal, (b) on-device acoustic prosody (RMS energy, F0 pitch estimate, speaking rate syllables/sec, pause ratio), and (c) interaction context (repeated commands/corrections = frustration, time of day). | API 26 | Module 10 |
| **F-094** | Smoothed mood output across 9 labels (`CALM`, `HAPPY`, `EXCITED`, `TIRED`, `SAD`, `STRESSED_HURRIED`, `ANGRY_FRUSTRATED`, `CONFUSED`, `NEUTRAL`) with `intensity` (0.0–1.0) and `confidence` (0.0–1.0) using exponential moving average so mood never flip-flops on a single noisy turn. | API 26 | Module 10 |
| **F-095** | Dynamic response & TTS prosody adaptation: `STRESSED_HURRIED` -> ultra-short & slightly faster; `SAD`/`TIRED` -> warm, gentle, slower; `HAPPY`/`EXCITED` -> upbeat & playful; `ANGRY_FRUSTRATED` -> calm, apologetic on error, zero anger mirroring; `CONFUSED` -> simple numbered steps. | API 26 | Module 10 |
| **F-096** | Distress & self-harm safety guardrail: if serious distress or self-harm language is detected, respond with immediate care and provide trusted contact / local emergency helpline options (`1122` / `1166` in Pakistan, `988` / `112` internationally). | API 26 | Module 10 |
| **F-097** | Settings controls for Mood Adaptation (`ON/OFF`), Mood History persistence (`ON/OFF`), and live Mood Indicator badge on the Home Orb screen. | API 26 | Module 10, 12 |

---

## Section 11: Lock Screen, Biometrics & Security (`F-098` – `F-104`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-098** | Screen-off & lock-screen operation: wake screen (`PowerManager.SCREEN_BRIGHT_WAKE_LOCK` / `turnScreenOn`), render over lock screen (`setShowWhenLocked(true)` on API 27+ + `RoleManager.ROLE_ASSISTANT`), and speak responses. | API 26 (API 27 `setShowWhenLocked`) | Module 11 |
| **F-099** | Locked-device verification gate for medium/high-risk actions: PBKDF2-HMAC-SHA256 salted passphrase hash + on-device MFCC/spectral speaker embedding cosine verification, anti-replay challenge word support, and 3-strike 5-minute lockout + intruder alert log. | API 26 | Module 11 |
| **F-100** | Multi-tier Unlock Ladder: `KeyguardManager.requestDismissKeyguard` -> Accessibility swipe + Keystore-encrypted PIN/pattern entry (`OmniAccessibilityService`) -> Smart Lock / Extend Unlock guidance. PIN is stored exclusively in Android Keystore + encrypted prefs, masked in UI, never logged or sent to cloud. | API 26 | Module 11 |
| **F-101** | Strict 3-Tier Risk Classification (`LOW`: time, weather, torch; `MEDIUM`: calls, messages, settings; `HIGH`: payments, banking apps, data deletion, password changes, app installs, factory reset) requiring explicit spoken confirmation or passphrase. | API 26 | Module 5, 11 |
| **F-102** | Prompt-injection firewall wrapping all external text read from screens, notifications, SMS, WhatsApp, web pages, or files in `<untrusted_data>` tags and stripping imperative tool-override patterns. | API 26 | Module 8, 11 |
| **F-103** | Tamper-evident Audit Log in Room recording timestamp, raw command, tool executed, lock state, verification status, and verified outcome. | API 26 | Module 11 |
| **F-104** | Instant **"Stop Everything" Panic Phrase** (`"stop everything"`, `"sab roko"`, `"emergency stop"`) that aborts all running coroutines, UI automation macros, and audio output immediately. | API 26 | Module 2, 6, 11 |

---

## Section 12: First-Run Permission Wizard & Real-Time Diagnostics (`F-105` – `F-108`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-105** | Step-by-step **First-Run Permission Wizard** with plain-language explanations (English, Urdu, Roman Urdu) and deep-links to exact system settings screens for all runtime and special permissions. | API 26 | Module 1 |
| **F-106** | Complete permission coverage: `RECORD_AUDIO`, `READ/WRITE_CONTACTS`, `READ/WRITE_CALL_LOG`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `SEND/READ/RECEIVE_SMS`, `READ/WRITE_CALENDAR`, `CAMERA`, `POST_NOTIFICATIONS` (API 33+), Wi-Fi/Network state, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES` (API 33+), `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (API 31+), Media read permissions, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (API 34+), `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `QUERY_ALL_PACKAGES`, Battery Optimization Exemption, Exact Alarms (`SCHEDULE_EXACT_ALARM` API 31+), DND Access, Usage Access, Accessibility Service, Notification Listener, Device Admin, Default Assistant Role (`RoleManager.ROLE_ASSISTANT` API 29+), and guided `WRITE_SECURE_SETTINGS` / Shizuku setup. | API 26–36 | Module 1 |
| **F-107** | Re-openable Red/Green **Permissions Dashboard** with one-tap **"Fix This"** buttons and live verification on `ON_RESUME`. | API 26 | Module 1 |
| **F-108** | Comprehensive **Diagnostics Screen**: live mic dB/RMS meter, hardware `AEC`/`NS`/`AGC` status, interactive **Echo Test** (pass/fail), installed offline STT & TTS voices (`ur-PK`, `en-US`), on-device LLM status + tokens/sec benchmark, Gemini cloud latency test, `NetworkMonitor` state, wake-word engine status, Accessibility service heartbeat, OEM battery/autostart advisor, human-readable error names, and secret-redacted log exporter. | API 26 | Module 1, 12 |

---

## Section 13: User Interface & Typography (`F-109` – `F-114`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-109** | Material 3 Dark/Light theme + toggleable **"Jarvis HUD"** futuristic theme, with full English & Urdu UI localization. | API 26 | Module 12 |
| **F-110** | Home screen header showing custom Assistant Name, Online/Offline badge, and active Mood pill; centerpiece animated Orb reflecting states (`STANDBY`, `LISTENING`, `THINKING_ACTING`, `SPEAKING`, `ERROR`) with mood-tinted aura and 60s standby countdown ring. | API 26 | Module 12 |
| **F-111** | Live bilingual transcript view with proper bidirectional RTL/LTR layout and bundled **Noto Nastaliq Urdu** font (`res/font/`) paired with clean Latin typography for mixed Urdu/English sentences. | API 26 | Module 12 |
| **F-112** | Verified **Action Result Cards** (`Success` / `Failed` + exact reason + one-tap **Undo** where reversible) and **Pending Task Card** (e.g., *"Waiting for Wi-Fi connection to resume Google Search..."*). | API 26 | Module 12 |
| **F-113** | 5-Tab Bottom Navigation (`Home`, `History`, `Routines`, `Memory`, `Settings`) + complete Settings screen (Assistant Name & Wake Phrase, Standby Timeout, Language Mode, Voice Picker & Preview, Privacy Mode Offline-Only toggle, On-Device Model Download Manager, Permissions Dashboard, Mood Options, Encrypted Backup/Restore). | API 26 | Module 12 |
| **F-114** | Lock-screen overlay UI, haptic feedback (`HapticFeedbackType`), minimum 48dp touch targets, TalkBack `contentDescription` semantics, and Compose `testTag` identifiers on all controls. | API 26 | Module 12 |

---

## Section 14: Quality Gates & Verification Suite (`F-115` – `F-120`)

| ID | Requirement Summary | Min API | Target Module |
|---|---|---|---|
| **F-115** | Global `UncaughtExceptionHandler` with crash-loop protection and structured secret-redacted ring-buffer logger. | API 26 | Module 1, 13 |
| **F-116** | Automated JUnit/Robolectric unit tests for `AssistantAudioStateMachine`, `DeterministicCommandParser`, `LanguageProcessor`, `SecurityManager`, and `ActionVerifier`. | API 26 | All Modules |
| **F-117** | Mock `AccessibilityNodeInfo` tree test validating the complete 9-step Connectivity Assistant Wi-Fi discovery, password entry, and task auto-resume flow. | API 26 | Module 7, 13 |
| **F-118** | **Double-Voice Regression Test** proving `AudioOutputManager` and `OmniLiveSessionEngine` never emit two concurrent audio streams for the same turn. | API 26 | Module 2, 3, 13 |
| **F-119** | **Airplane-Mode Offline Test Suite** & 300+ bilingual (English, Urdu Nastaliq, Roman Urdu, Mixed) benchmark runner with accuracy & latency report (<3s offline, <2s online). | API 26 | Module 4, 8, 13 |
| **F-120** | Phase 13 Final Audit Report verifying all `F-001`–`F-120` items, real-phone installation guide, and complete Android restriction vs. workaround matrix. | API 26 | Module 13 |
