# Still

Privacy-first Android MVP for context-aware availability.

![Still presence screen](docs/screenshots/still-presence.png)

## What v1 Does

- Reads accelerometer and light sensor on device.
- Lets user set ambient audio, heart rate, peripheral, and device-place hints manually.
- Uses Android AICore/Gemini Nano through ML Kit GenAI Prompt API to predict the current state from coarse tokens.
- Falls back to deterministic fuzzy sensor logic when Android AICore is unavailable or still initializing.
- Generates one-line human auto-reply without network calls.
- Shows privacy guardrails in UI.
- Shows current availability on lock screen through a public notification.

## Build

Open in Android Studio or run Gradle from local install:

```sh
gradle :app:assembleDebug
```

This repo intentionally avoids app-owned network permissions, cloud services, and external model calls in v1. Android AICore model setup is handled by the system service.
