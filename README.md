# SmartMeterScale

Native Android app that photographs utility meters and body scales, reads the displayed values via on-device OCR, and sends them as sensors to Home Assistant.

## Motivation

Taking photos of gas meters, electricity meters, and body scales is a simple way to log readings without installing smart sensors. This app closes the last gap: turning those photos into actual Home Assistant sensor data automatically, without any cloud dependency.

## Features

### Current
- OCR value parsing from scale display photos (weight, body fat %, body water %)
- Input validation with plausible range checks per measurement type
- Home Assistant REST API integration (`/api/states`)
- Unit tests for parsing and validation logic

### Planned
- CameraX live viewfinder with capture button
- ML Kit Text Recognition integration (on-device, no cloud)
- One-time display region calibration (crop to the display area for reliable reads)
- Support for gas and electricity meter formats
- Persistent configuration (HA base URL, long-lived access token)
- Syncthing folder drop: save photo to a watched folder as an alternative trigger

## Architecture

```
Camera / Photo picker
        ↓
  ML Kit OCR (on-device)
        ↓
  OcrValueParser        ← regex-based, handles decimal comma/dot, noise
        ↓
  ReadingValidator      ← plausible range checks per field
        ↓
  HomeAssistantClient   ← HTTP POST to /api/states via OkHttp
```

**Package:** `de.simonroder.smartmeterscale`

| Package | Contents |
|---------|----------|
| `data`  | `ScaleReading` data class |
| `ocr`   | `OcrValueParser`, `ReadingValidator` |
| `ha`    | `HomeAssistantConfig`, `HomeAssistantClient` |

## Requirements

- Android 8.0+ (API 26)
- Android Studio Hedgehog or newer
- JDK 17

## Build

Open the project in Android Studio — it will download the Gradle wrapper and dependencies automatically.

From the command line (after Android Studio has set up the wrapper):

```bash
# Debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run unit tests with report
./gradlew test --continue
# Report: app/build/reports/tests/testDebugUnitTest/index.html
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Home Assistant Setup

1. In HA, create a **Long-Lived Access Token** under your profile → Security
2. Enter the token and your HA base URL (e.g. `https://yourname.duckdns.org`) in the app settings

The app will create or update these sensors automatically on each reading:

| Entity | Unit | Description |
|--------|------|-------------|
| `sensor.scale_weight` | kg | Body weight |
| `sensor.scale_body_fat` | % | Body fat percentage |
| `sensor.scale_body_water` | % | Body water percentage |

## Contributing

Contributions welcome. Please open an issue before starting larger features.

## License

[MIT](LICENSE)
