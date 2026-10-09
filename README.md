# AntiRecordingShield v0.3 — JDK 25 build environment

This project is configured for a Gradle process running with JDK 25 while compiling Java/Kotlin source to JVM target 17 for Android compatibility. JDK runtime version and bytecode target are different settings; do not set `jvmTarget = "25"` for this Android app.

## Build

```bash
java -version
./gradlew --version
./gradlew clean assembleDebug --warning-mode all
```

If the project does not include a Gradle wrapper, use the Gradle version configured by your Android Studio / build environment:

```bash
gradle clean assembleDebug --warning-mode all
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Important detection limitation

The “possible recording” indicator is a heuristic acoustic-anomaly indicator. An ordinary phone app cannot reliably determine whether a nearby, separate phone is internally recording. Treat the score as experimental, not proof.
