# Yomikomi

A minimal native Android project using Kotlin, Jetpack Compose, and Gradle Kotlin DSL.

The single `app` module contains a launcher activity with an empty Compose content block. No business features, sample screens, or architecture layers are included.

## Requirements

- JDK 17
- Android SDK Platform 36 and Build Tools 36.0.0
- Android device or emulator running Android 6.0 (API 23) or newer

Open the project root in Android Studio and sync Gradle. For command-line builds, set `ANDROID_HOME` to your SDK directory or configure `sdk.dir` in an untracked `local.properties` file.

## Build and run

On macOS or Linux:

```sh
./gradlew assembleDebug lintDebug
./gradlew installDebug
adb shell am start -n io.github.yomikomi/.MainActivity
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug lintDebug
.\gradlew.bat installDebug
adb shell am start -n io.github.yomikomi/.MainActivity
```

Installation requires a connected device or running emulator. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The Gradle Wrapper pins Gradle 8.13. Plugin and library versions are declared in the root and app build scripts. The first build requires network access to download Gradle and dependencies. No tests are included because the project has no application behavior yet.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
