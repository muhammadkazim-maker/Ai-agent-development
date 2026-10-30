# Phase 0 (d): Module Build Order (`Modules 0–13`) & Verification Test Plan

---

## 1. Sequential Build Order (Section 17 — One Module Per Reply)

| Module | Title | Covered Requirements | Key Deliverables |
| :---: | :--- | :--- | :--- |
| **Module 0** | **Phase 0 Engineering Blueprints** | `R1`–`R18` (Traceability & Design) | `/docs/01` through `/docs/05` (Traceability Matrix, Android 12–17 Feasibility, Architecture & Tiers, Build Order, Conflicts & Honest Alternatives). |
| **Module 1** | **Project Skeleton (`minSdk=31`) + Capability Probe + Permission Wizard + Diagnostics** | `R1`, `R16`, `R17` | Set `minSdk = 31`; create `Android12To17Compat.kt`, `CapabilityProbe.kt` (`Tier A/B/C/D`, OEM detector, Dual-SIM, Android 17 `AdvancedProtectionManager` check), `OmniRecognitionService.kt`, updated `PermissionsSetupScreen.kt` (Assistant Role, Restricted Settings, Auto-Reset off, Shizuku), and `DiagnosticsScreen.kt` (`Setup Health` + Capability Profile + all-zero mic check + audio suppression check). |
| **Module 2** | **Always-On Engine: `VoiceInteractionService`, Mic FGS, Audio Engine, State Machine, Wake Phrase & Standby** | `R1`, `R10`, `R11`, `R12` | Enforce the **Always-On Engine Rule** (never start mic FGS from `BOOT_COMPLETED`; use `ROLE_ASSISTANT` or 1-tap notification fallback), floating mini-orb overlay, 60s auto-standby, wake phrases (`"<Name>, wake up"`, `"<Name> jaag jao"`), custom voice rename, and permanent fix for the 3 audio bugs. |
| **Module 3** | **STT / TTS (Online + Offline) + Speech Normaliser** | `R5`, `R7`, `R10` | Android 13+ On-Device `SpeechRecognizer` (`createOnDeviceSpeechRecognizer`, `triggerModelDownload` for `ur-PK`/`en`), `AudioOutputManager` 24 kHz jitter buffer, `SpeechNormaliser` (`displayText` vs. `speechText`, language-run splitting), and voice picker with preview. |
| **Module 4** | **Language Layer + Datasets + System Prompts** | `R7`, `R9` | Urdu/Roman-Urdu/English normalisation (`LanguageProcessor`), `assets/vocabulary.json`, `assets/commands_dataset.json`, `assets/stt_corrections.json`, `assets/system_prompt_cloud.txt`, and `assets/system_prompt_local.txt`. |
| **Module 5** | **Contacts, WhatsApp, Dual-SIM & Core Actions with Verified `ActionResult`** | `R3`, `R5`, `R9`, `R17` | Multi-account `ContactsContract` loader (Phone, SIM, Google, WhatsApp `com.whatsapp` & `com.whatsapp.w4b`), Dual-SIM call/SMS routing (`"SIM 2 se call karo"`), app registry with Urdu/English aliases, and real `ActionVerifier` read-backs. |
| **Module 6** | **Accessibility Engine + Control Ladder (`L1 -> L2 -> L3 -> L4`) + Android 17 Advanced Protection** | `R3`, `R4`, `R9`, `R17` | Truthful `accessibility_service_config.xml` (no false `isAccessibilityTool`), `AdvancedProtectionManager` & `isAccessibilityDataSensitive` detection, 11-brand OEM selector packs (`oem_selectors.json`), and LLM self-healing tree fallback. |
| **Module 7** | **Connectivity Assistant (9-Step Offline-to-Online Wi-Fi & Spoken Password Flow)** | `R4`, `R6`, `R9` | `NetworkMonitor` (`NET_CAPABILITY_VALIDATED`, captive portal check), pending task queue, `L1 -> L2 -> L3` Wi-Fi/Data radio enablement, SSID scan & read-out, `OnDeviceSpellingRecognizer` password capture + character read-back, Keystore save offer, and auto-resume. |
| **Module 8** | **On-Device LLM + Cloud Gemini + Hybrid Router + Multi-Step Agent Loop** | `R5`, `R8`, `R9` | Capability-tier router (`Tier A/B/C/D`), AICore Gemini Nano / LiteRT-LM `.litertlm` model download & storage import manager, Cloud Gemini with Google Search grounding, Privacy Mode (Offline-Only), strict tool validator, and chained/conditional agent loop. |
| **Module 9** | **Memory & Teaching (Permanent Room + Keystore Vault)** | `R12`, `R13` | Immediate Room + Android Keystore `AES/GCM/NoPadding` persistence for taught facts, aliases, routines, STT corrections, preferences, consented Wi-Fi passwords, conversation summarization, and encrypted backup export/import. |
| **Module 10** | **Mood Awareness (9 States) & Personality Adaptation** | `R14`, `R15` | 9-state EMA-smoothed `MoodAnalyzerEngine` (lexicon + prosody + repeated-command frustration), adaptive TTS speed/pitch/warmth, and Self-Harm Helpline Guardrail (`1166`/`1122` PK, `988`/`112` global). |
| **Module 11** | **Lock Screen, Spoken Passphrase, Speaker Verification & Unlock Ladder** | `R1`, `R2`, `R9` | `showWhenLocked` / `turnScreenOn`, salted passphrase hash + speaker biometrics, 3-strike 5-minute lockout, honest Unlock Ladder (`L1` Keyguard dismiss $\rightarrow$ `L2` Accessibility PIN/swipe $\rightarrow$ `L3` Shizuku/ADB $\rightarrow$ Smart Lock), and `"stop everything"` panic phrase. |
| **Module 12** | **UI Polish & Adaptive Material 3 Jarvis HUD** | `R15`, `R16`, `R17` | Animated Orb with standby countdown ring, mood tint, RTL Noto Nastaliq Urdu + Latin mixed transcript, verified action cards with Undo, pending task banner, Setup Health view, and 5 bottom tabs (`Home, History, Routines, Memory, Settings`). |
| **Module 13** | **Signed Release Build, R8 Rules, Install Guide & Final Audit (`R1`–`R18`)** | `R18` | Release signing & `proguard-rules.pro`, full Robolectric + 300+ bilingual test run, per-brand & per-Android-version (`12–17`) test checklist, and ADB / sideload / Developer Verification install guide. |

---

## 2. Automated & Real-Phone Test Plan

1. **Automated JVM / Robolectric Suite (`gradle :app:testDebugUnitTest`)**:
   - `CapabilityProbeTierClassifierTest`: Verifies `Tier A / B / C / D` classification, OEM brand detection, Dual-SIM handling, and `Android12To17Compat`.
   - `DoubleVoiceRegressionTest`: Verifies `AudioOutputManager` allows strictly 1 output modality per `turnId` and drops duplicate `turnId`s.
   - `MockAccessibilityTreeWifiFlowTest`: Verifies the 9-step Wi-Fi selection, `OnDeviceSpellingRecognizer` password parsing (`"capital P a k underscore number 7 8 6"`), and Connect click over a simulated tree.
   - `BilingualBenchmark300PlusTest`: Runs 324+ English, Urdu Nastaliq, Roman Urdu, and Mixed commands and asserts $\ge 95\%$ intent, parameter, and reply-language accuracy.
   - `SecurityAndFirewallTest`: Verifies `<untrusted_data>` prompt-injection blocking, 9-state mood + distress helpline guardrail, and Keystore encrypted backup round-trip.
2. **Real-Phone Verification Script**:
   - **Airplane Mode Test**: Turn on Airplane Mode $\rightarrow$ test flashlight, volume, alarm, timer, app launch, offline chat, and trigger an online query (`"Google par search karo..."`) to walk through the 9-step Wi-Fi Connectivity Assistant.
   - **Lock-Screen & Reboot Test**: Lock the phone $\rightarrow$ trigger `"<Name>, wake up"` $\rightarrow$ test low-risk command (`"time kya hua hai"`) vs. medium/high-risk command with spoken passphrase. Reboot phone and verify `ROLE_ASSISTANT` auto-start or 1-tap boot notification.
