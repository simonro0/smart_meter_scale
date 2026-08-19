# SmartMeterScale

Native Android app that photographs utility meters and body scales, reads the displayed values via on-device OCR, and sends them as sensors to Home Assistant.

## Motivation

Taking photos of gas meters, electricity meters, and body scales is a simple way to log readings without installing smart sensors. This app closes the last gap: turning those photos into actual Home Assistant sensor data automatically, without any cloud dependency.

## Features

### Current
- CameraX live viewfinder with capture button
- Gallery picker for existing photos
- ML Kit Text Recognition (on-device, no cloud required)
- Optional Gemini API backend for significantly better LCD/7-segment recognition (API key from aistudio.google.com)
- OCR value parsing (weight, body fat %, body water %) with comma/dot handling and noise tolerance
- Manual image rotation (90° per tap) with automatic OCR retry — useful for sideways scale photos
- Support for gas, electricity, and water meters in addition to body scales
- Multi-user support for the scale (each user gets their own HA sensor)
- Home Assistant REST API integration (`/api/states`) via OkHttp
- Persistent configuration (HA URL, token, Gemini key, users) in SharedPreferences
- Optional backup folder (e.g. Syncthing Send-Only) with timestamp filenames
- Photos saved to `Android/media/de.simonroder.smartmeterscale/captures/`
- Unit tests for parsing and validation logic

### Planned
- One-time display region calibration (crop to the display area for reliable reads)
- Gemini Nano on-device support (Pixel 8+ / Android 14+) when the image API stabilises

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
| `data`  | `ScaleReading`, `MeterType`, `User` |
| `ocr`   | `OcrProcessor` (ML Kit), `GeminiOcrClient`, `OcrValueParser`, `ReadingValidator` |
| `ha`    | `HomeAssistantConfig`, `HomeAssistantClient`, `HaPreferences`, `UserPreferences` |
| `ui`    | `HomeScreen`, `CameraScreen`, `ResultScreen`, `SettingsScreen` |

## Requirements

- Android 8.0+ (API 26)
- Android Studio Hedgehog or newer
- JDK 17

## Build

Open the project in Android Studio — it will download the Gradle wrapper and dependencies automatically.

From the command line (after Android Studio has set up the wrapper):

```bash
# Debug APK (installierbar ohne Signatur-Setup)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (erfordert Signing-Konfiguration, siehe unten)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Run unit tests
./gradlew test

# Run unit tests with report
./gradlew test --continue
# Report: app/build/reports/tests/testDebugUnitTest/index.html
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Release-Signatur einrichten

Eine Release-APK muss signiert sein. Einmalig einen Keystore erstellen:

```bash
keytool -genkey -v -keystore smartmeterscale.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias smartmeterscale
```

Dann in `app/build.gradle.kts` eintragen:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../smartmeterscale.jks")
            storePassword = System.getenv("KEYSTORE_PASS") ?: "changeme"
            keyAlias = "smartmeterscale"
            keyPassword = System.getenv("KEY_PASS") ?: "changeme"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

Alternativ: In Android Studio **Build → Generate Signed Bundle/APK** führt durch den Prozess interaktiv.

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
