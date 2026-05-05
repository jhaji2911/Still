# Still: Product Prompt and MVP Roadmap

## 01. Product Vision

Still is a privacy-first availability engine for mobile. It uses local sensor signals and fuzzy context rules to infer what Nishant is doing, then produces a short human auto-reply that reduces interruption without sounding robotic.

Still is not a binary Do Not Disturb toggle. It is a local status proxy: calm, context-aware, and transparent about why it thinks the user is unavailable.

## 02. MVP Goal

Build the first Android version of Still as a native, on-device app.

The MVP should:

- Read low-power local sensor signals where available.
- Let the user manually simulate unavailable context when real sensor integrations are not ready.
- Fuse tokens into a predicted availability state.
- Generate a one-sentence auto-reply locally.
- Show the inferred state, confidence, reasoning, and current sensor tokens.
- Request only permissions that map to visible app behavior.
- Avoid any network permission or cloud dependency.

## 03. Current Android App Identity

- App name: `Still`
- Package name: `com.ninjha.still`
- Platform: Android
- Primary user: Nishant
- Brand direction: warm, minimal, grounded, private
- Core colors: bone, ink brown, moss green, clay accent

## 04. Core Concept: Fuzzy Sensor Logic

Still converts noisy device and wearable signals into stable context tokens. Each token is intentionally coarse to reduce privacy risk and avoid pretending to know more than the device can reliably infer.

| Signal | Token | Values |
| --- | --- | --- |
| Accelerometer | `MotionState` | `Still`, `MicroVibration`, `HighMotion` |
| Heart rate | `PhysioStress` | `Resting`, `Aerobic`, `Anaerobic`, `Unknown` |
| Ambient audio | `EnviroDecibel` | `Silent`, `Rhythmic`, `Chaotic` |
| Device placement | `DevicePlace` | `Pocket`, `FaceDown`, `ChargingStand`, `InHand` |
| Connectivity | `Peripheral` | `Headphones`, `CarBluetooth`, `None` |

## 05. MVP Inference Rules

The first version should use deterministic fuzzy scoring before adding on-device LLM inference. Deterministic rules make the product debuggable, cheap to run, and easier to trust.

### Training

Likely when:

- Motion is still or micro-vibration.
- Heart rate is elevated.
- Audio is rhythmic.
- Headphones are connected.

Reply:

```text
Nishant is in a focused workout block. He'll check his phone in about 10 mins.
```

### Meditating

Likely when:

- Motion is still.
- Audio is silent.
- Phone is face down.
- Heart rate is resting.

Reply:

```text
Nishant is likely in a quiet focus or meditation session. He'll respond in about 20 mins.
```

### Commuting

Likely when:

- Motion is high or vibration-like.
- Car Bluetooth is connected.
- Audio is chaotic.

Reply:

```text
Nishant is likely commuting. He'll get back once he parks.
```

### Deep Work

Likely when:

- Motion is low.
- Phone is face down or on a stand.
- Audio is stable.
- Headphones are connected.

Reply:

```text
Nishant is likely in deep work. He'll reply when he comes up for air in about 45 mins.
```

### Available

Fallback when no strong unavailable state is detected.

Reply:

```text
Nishant seems available but may not be looking at his phone. He'll reply soon.
```

## 06. MVP App Screen

The first screen should act as a sensor dashboard and trust surface.

Show:

- Still logo and short privacy statement.
- Current inferred state.
- Confidence percentage.
- One-line auto-reply.
- Active token values.
- Reason list explaining why the inference won.
- Manual hint controls for audio, heart rate, device placement, and peripherals.

Avoid:

- Cloud sync.
- Account creation.
- Push notifications.
- Contact access.
- Notification auto-replies before explicit user review and permission education.

## 07. Privacy Manifesto

Still should be designed as zero-cloud by default.

- No sensor data leaves the device.
- No internet permission in MVP.
- No background listening in MVP.
- Audio should begin as a user-selected token, not raw microphone capture.
- Future model downloads must be separated from sensor processing.
- Any future network permission must be justified in onboarding and isolated from telemetry.

## 08. Native Technical Direction

### Android

- Kotlin.
- Native Android sensors for v1.
- Deterministic core inference module shared by UI.
- Package: `com.ninjha.still`.
- Later: Activity Recognition, Notification Listener, optional Accessibility bridge only after clear permission design.

### iOS

- Swift and SwiftUI later.
- CoreMotion and Apple Watch signals later.
- Keep token model aligned with Android.

### AI Engine Later

Use on-device LLM inference only after deterministic rules prove useful.

Candidate responsibilities for local AI:

- Rewrite replies in a warmer tone.
- Personalize return estimates from local history.
- Explain uncertainty in plain language.
- Detect contradictory context tokens.

Do not use an LLM for basic state scoring until battery, latency, and memory behavior are measured.

## 09. Development Roadmap

### Phase 1: Android Sensor Dashboard

- Build Android app skeleton.
- Add Still branding and launcher logo.
- Read accelerometer and available low-risk sensors.
- Add manual token controls.
- Implement deterministic fuzzy scoring.
- Display state, confidence, reply, and reasons.

### Phase 2: Trust and Permissions

- Add onboarding that explains each permission before request.
- Add local-only privacy copy.
- Add clear pause/disable controls.
- Add local inference log view with delete-all action.

### Phase 3: Wearable and Activity Context

- Add heart rate source integration.
- Add Activity Recognition where available.
- Add commuting mode tuned for Indian traffic patterns.
- Add confidence smoothing across a rolling sensor window.

### Phase 4: Communication Bridge

- Add contact allowlist.
- Add notification listener preview mode.
- Let the user approve suggested replies before automation.
- Add strict safeguards for urgent contacts.

### Phase 5: On-Device AI

- Evaluate local model size, latency, RAM, and battery cost.
- Use local model only for reply phrasing or uncertainty explanation.
- Unload model aggressively when idle.
- Keep deterministic rules as fallback.

## 10. Technical Risks

- Permission fatigue: motion, body sensors, notifications, and accessibility require careful sequencing.
- Battery drain: continuous sensors must be low-frequency and gated.
- False confidence: UI must show reasons and uncertainty.
- RAM pressure: on-device models can be expensive on mid-range phones.
- Trust risk: auto-replies must start as suggestions before automation.

## 11. Build Prompt for Codex

Build v1 of Still as an Android app from this spec.

Implementation requirements:

- Use package `com.ninjha.still`.
- Keep inference logic in a separate core module where practical.
- Implement deterministic fuzzy scoring first.
- Include a single Android screen showing logo, privacy copy, inferred state, confidence, auto-reply, active tokens, and reason list.
- Use manual controls for unavailable signals not yet connected to real sensors.
- Do not add internet permission.
- Verify with a debug build.

Definition of done:

- App builds and installs on a connected Android device.
- Package name is `com.ninjha.still`.
- Launcher uses Still logo.
- User can change manual hints and see inferred state update.
- Auto-reply and reason list update locally without network access.
