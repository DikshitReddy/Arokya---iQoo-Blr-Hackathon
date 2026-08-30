<div align="center">

# Arokya — On-Device AI Health Coach

**A health coach whose AI brain runs entirely on your phone — and is built so the model cannot lie to you about your numbers.**

`Kotlin 2.4.10` · `Jetpack Compose` · `LiteRT-LM · Gemma 4 E2B` · `Android 8.0+` · `~11.4k LOC`

</div>

---

## What it is

Arokya is a native Android app whose intelligence — chat, food vision, meal planning, reminders — is produced by a **Gemma 4 E2B** model executing **in-process on the device**. No cloud inference, no account, no server round-trip. It works with the network fully off.

Two ideas define the architecture:

1. **The engine decides, the AI explains.** A deterministic rule engine (`engine/`) owns *every* number — calories, macros, MET-based activity burn, thresholds — and emits recommendations with a `ruleId` and a traceable "why this?" reason list. The model is only allowed to rephrase the message around those numbers, so it physically **cannot hallucinate your health math**.
2. **Verifiable on-device inference.** Every answer carries a *measured* chip — e.g. `Gemma 4 E2B · CPU · 4.2s · 312 tok` — real wall-clock and token count, so a sceptic can see each answer ran on this phone.

## What's genuinely novel

- **On-device multimodal health coach** — one small model does both text *and* vision (reads a plate of food, a lab-report PDF, reel frames), all on the handset.
- **Safety by construction** — the deterministic engine guards the LLM, so recommendations are reproducible and explainable.
- **Reel → recipe by vision** — share an Instagram food reel and Arokya reconstructs the recipe, macros, and a lower-calorie version, then offers a *cook-it / order* fork.
- **Agentic without native function-calling** — a prompt-driven `THINK / CALL / ASK / FINAL` loop drives real tools (set an `AlarmManager` reminder, read live health data), and *asks* for a time instead of guessing when the request is vague.
- **Inclusive** — one base prompt serves **23 Indian languages** via dynamic language injection (no per-language hardcoding).

## Tech stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin 2.4.10 (JVM 17) | AGP 8.6.1, Gradle 8.14.4 |
| UI | Jetpack Compose (BOM 2024.09.03) + Material 3 | Single-Activity, `navigation-compose` |
| On-device AI | `com.google.ai.edge.litertlm` | One `Engine`, CPU backend, text + vision |
| Model | Gemma 4 E2B `.litertlm` (~2.4 GB) | Lives in the app's external `files/models/` |
| Camera | CameraX 1.3.4 | Live preview + capture for the scanner |
| Background | WorkManager 2.9.1 | Rewrites nudge notification copy |
| Persistence | Hand-rolled `SQLiteOpenHelper` (no Room) | 6 tables, schema v6 |
| Crypto | AndroidKeyStore + AES-256-GCM | Field-level encryption of PII & lab data |
| Speech / Sensors | `TextToSpeech`, `SpeechRecognizer`, `STEP_COUNTER` | All platform APIs |
| Scheduling | `AlarmManager` + `NotificationManager` | Exact nudges & user reminders |

> **Deliberate omissions.** No Room (its codegen can't read Kotlin 2.4.x metadata, and 2.4.10 is required by LiteRT-LM). No SQLCipher (its current release needs compileSdk 37). Both gaps are closed with plain-platform code.

## Project structure

```
app/src/main/java/com/arokya/app/
  MainActivity.kt        navigation graph, share/assist deep links, startup wiring
  engine/                deterministic core — RuleEngine, ActivityBurn (MET),
                         NutritionTargets, PlanLever, MealSlot
  ml/                    LiteRT-LM runtime (MlEndpoints), tool-calling loop,
                         coaching prompts, reel analysis, Instagram fetch, stats
  data/  data/db/        models, repos, SQLiteOpenHelper, DAOs, FieldCrypto, languages
  nudge/                 AlarmManager scheduling, receivers, WorkManager copy worker
  reminder/              user reminders, fire receiver, boot re-arm
  sensor/StepSensor.kt   STEP_COUNTER pedometer -> live step state
  speech/Speaker.kt      TextToSpeech with sentence-level pause/resume
  assistant/             Quick Settings "Talk to Arokya" tile
  ui/screens/            one file per flow (Home, Scan, VoiceCoach, Activity, …)
  ui/theme/              Poppins typography + palette
```

A full architecture write-up with layered diagrams, the tool-calling sequence, and the data model is maintained separately as a shareable one-pager.

## Build & run

**Prerequisites**

- Android Studio (latest stable).
- **JDK 21.** Android Studio bundles JBR 25, which Gradle 8.14.4 refuses to run on. The Gradle daemon is pinned to a local JDK 21 via `org.gradle.java.home` in `gradle.properties` — **update that path to your machine** (or remove the line and point Studio's Gradle JDK at any JDK 21).
- A physical Android phone (Android 8.0+). Skip the emulator — it's slow and misreports on-device ML performance.

**Steps**

1. Open the `Arokya` folder in Android Studio and let Gradle sync finish.
2. Enable Developer options + USB debugging on the phone, connect it, accept the prompt.
3. Press **Run ▶**. The app installs and opens.

### Provisioning the model (required for AI features)

The ~2.4 GB `.litertlm` model is **not** bundled in the APK. Push it to the app's external files directory once:

```bash
adb shell run-as com.arokya.app mkdir -p files/models   # or push to the external path below
adb push <your-model>.litertlm \
  /sdcard/Android/data/com.arokya.app/files/models/gemma-4-E2B-it.litertlm
```

Any `*.litertlm` file in `files/models/` is picked up automatically; the filename becomes the label on the inference chips.

> ⚠️ **Never `adb uninstall` this app.** Uninstalling wipes `Android/data/com.arokya.app/files/`, which deletes the 2.4 GB model. Always reinstall with `adb install -r` to keep it.

### Permissions

Requested at real system dialogs, none silent: notifications, exact alarms, boot-completed, record-audio, camera, activity-recognition.

## Before shipping

Two flags are currently set to test values in `nudge/`:

- `Nudge.INTERVAL_MINUTES` → set back to `480` (the 8-hour cadence; currently `1` for testing).
- `NudgeAlarmReceiver.ALWAYS_NUDGE` → set to `false` (currently `true`).

## System integration surfaces

Launcher shortcut → voice · the `ASSIST` gesture (set Arokya as the digital-assistant app) · a Quick Settings tile · share targets for `video/*` and `text/plain` (reel URLs) · an adaptive launcher icon.

---

<div align="center">
<sub>On-device · privacy-first · offline-capable. The model never leaves the phone.</sub>
</div>
