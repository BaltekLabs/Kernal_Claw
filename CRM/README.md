# CRM Android App (Local Project)

This folder contains a full Android project for a simple CRM MVP that can:

- Import contacts, call logs, and SMS from device content providers
- Store imported records locally with Room
- Export contacts as a VCF file and share it
- Show import summary counts in a Compose UI

## Build locally

1. Open `CRM/` in Android Studio (Hedgehog+ recommended).
2. Let Gradle sync and install required Android SDKs.
3. Build from Android Studio: `Build > Build APK(s)`.

If you prefer CLI builds, first generate/add a Gradle wrapper (`gradlew`/`gradlew.bat`) or install Gradle locally, then run:

```powershell
cd CRM
gradle assembleDebug
```

## APK output

`CRM/app/build/outputs/apk/debug/app-debug.apk`
