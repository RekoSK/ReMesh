# ReMesh Mobile App — Working Rules

These rules govern work on the **ReMesh Android mobile app**. They are the durable
record of the conventions agreed with the user, so they survive `/clear`.
Read and follow them at the start of every session.

## 1. Branch
- Do **all** development on branch `Re16`. Commit and push there.
- **Never** open a pull request unless the user explicitly asks.

## 2. Scope
- Only touch the `mobileapp/` directory (the Android app).
- Do **not** modify the firmware / C++ side of the repo (`src/`, `lib/`, `arch/`,
  `variants/`, `boards/`, `platformio.ini`, etc.).

## 3. Build & deliver on every message
After **every** change, from `mobileapp/`:
- Build the debug APK: `./gradlew assembleDebug`
  - If the wrapper can't fetch the distribution (proxy 403 on the Gradle
    download), use the pre-extracted Gradle offline instead:
    `/root/.gradle/wrapper/dists/gradle-9.4.1-bin/*/gradle-9.4.1/bin/gradle assembleDebug --offline`
- Debug signing uses the standard auto-generated Android debug keystore
  (`~/.android/debug.keystore`) — no explicit signing config is needed.
- Output APK: `mobileapp/app/build/outputs/apk/debug/app-debug.apk`
- Zip that APK and send it to the user (via SendUserFile) as a `.zip`.

Build environment reference:
- JDK 21, Gradle wrapper (`./gradlew`), Android SDK at `/root/android-sdk`.
- App module: `:app`, package `com.rekosk.remesh`, minSdk 35, targetSdk 36,
  compileSdk 36. Jetpack Compose + Material3.

## 4. Design / theming
- Fresh **Android 16** look, using **dynamic system colors** (Material You) —
  already wired into the project.
- PFP (profile picture) backgrounds and name colors are **dark-amber**-like —
  already defined in the project; reuse the existing colors, don't invent new ones.
