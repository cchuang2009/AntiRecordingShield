# Anti-Recording Shield 0.4.0 (Android)

A polished experimental acoustic-privacy prototype using the supplied shield icon.

## What it does
- Requests microphone permission at runtime.
- Shows a live FFT-derived speech-band energy indicator and spectrum bars.
- Generates low-level masking audio when Adaptive Masking is enabled.
- Provides start/stop controls and a masking intensity slider.

## Important limitations
This app **cannot detect whether another phone is recording**. The on-screen percentage is an acoustic activity score, not the probability that a recording device is present. It cannot guarantee that speech will be hidden from recording or speech recognition. The masking audio is experimental; test at low volume and do not use it to interfere with people or emergency communications.

## Build on Manjaro Linux
Recommended: JDK 21, Android Studio with Android SDK Platform 36 and Build Tools installed.

The project uses Android Gradle Plugin 8.10.1, Gradle 8.13 and Kotlin 2.0.21. Android bytecode target is Java 17, which is independent of the JDK used to run Gradle.

1. Open this directory in Android Studio and allow Gradle sync to finish.
2. Ensure SDK Platform 36 is installed in SDK Manager.
3. Build with **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or from a terminal where Gradle is installed:

   ```bash
   gradle assembleDebug
   ```

4. APK output: `app/build/outputs/apk/debug/app-debug.apk`.
5. Install to a USB-debugging-enabled Pixel: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

If you have an existing `gradlew` and `gradle/wrapper/gradle-wrapper.jar` from Android Studio, use `./gradlew assembleDebug` instead.

## Google Play readiness checklist
- Set a real publisher name and final application ID before first production release; application IDs cannot be changed after publishing.
- Provide a public privacy policy URL and complete the Play Console Data safety form. This prototype requests microphone access and processes microphone audio locally; audit this claim before release and disclose behavior accurately.
- Prepare store listing graphics/screenshots and content rating.
- Test on physical devices, including audio routing, interruptions, permission denial and background/foreground transitions.
- Build a signed Android App Bundle (AAB) with a production signing key. Do not upload a debug APK to production.
- Review current Google Play target API and policy requirements before submission.

The generated icon is used as the app launcher icon. The icon image is also supplied in `artwork/01_play_store_icon_512.png`.


## Suspected-recording environment alert (experimental)

The app now raises an in-app amber warning ("疑似錄音環境") and a short vibration when sustained speech-like acoustic energy remains above a heuristic threshold for multiple analysis frames. The warning clears after the input quiets down. This is only an acoustic heuristic: it cannot determine whether a nearby phone is recording, and speech, TV audio, music, or other noise may cause false positives. The percentage is an acoustic activity index, not a probability that recording is occurring. Test in real environments and do not market this as a confirmed hidden-recording detector.
