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

## 5. PFP / point colour palette (do not reinvent)
The per-node/per-point colours all come from ONE palette defined in
`mobileapp/app/src/main/java/com/rekosk/remesh/ui/components/NodeAvatar.kt`.
Always reuse it — never hardcode new colours for avatars, map markers, or
signal-coverage points.

- Two theme-tuned lists of **14 Material-500/400 colours**, index-aligned
  (same index = same hue family):
  - `AvatarPaletteLight` (Material 600-weight, for light theme):
    `E53935, D81B60, 8E24AA, 5E35B1, 3949AB, 1E88E5, 039BE5, 00897B,
     43A047, 7CB342, FB8C00, F4511E, 6D4C41, 546E7A`
  - `AvatarPaletteDark` (Material 400-weight, brighter, for dark theme):
    `EF5350, EC407A, AB47BC, 7E57C2, 5C6BC0, 42A5F5, 29B6F6, 26A69A,
     66BB6A, 9CCC65, FFA726, FF7043, 8D6E63, 78909C`
- **Deterministic per-contact colour**: `avatarColor(seed, dark)` hashes a
  stable seed (the contact id / public key) into the palette:
  `idx = ((seed.hashCode() % 14) + 14) % 14`.
- **Explicit colour by index** (user-picked, e.g. coverage points):
  `avatarColorByIndex(index, dark)`; `PALETTE_SIZE = 14`.
- **Tonal rendering rule** (what makes it look like the contacts list): the
  disc background is the colour at **22% alpha over the surface** and the
  glyph/letter is the **full-strength colour** (see `NodeAvatar`, and
  `nodeMarkerBitmap(..., tonal = true, backing = colorScheme.background)`
  in `MapScreen.kt` for map markers). Solid full-saturation discs read
  "brighter than the contacts" — the user explicitly does not want that.
- Node-type accents (repeater amber `F39C12`, etc.) live in `NodeColors`
  (`ui/theme/Color.kt`) and are used for non-chat node types.

## 6. Loading indicators (always use these, never plain spinners)
The user wants the **Material 3 expressive** loaders everywhere, consistently:

- **Themed morphing shape** — `androidx.compose.material3.LoadingIndicator`
  (`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`). NEVER use plain
  `CircularProgressIndicator`. Variants already in the app:
  - **Over a map / full-screen wait**: centred on a rounded backdrop —
    `Surface(color = surfaceContainerHigh.copy(alpha = 0.92f), shape =
    RoundedCornerShape(...))` + `LoadingIndicator` (+ optional label). See
    `MapLoadingIndicator` in `ui/screens/LineOfSightScreen.kt` (shared by the
    map tools) and the online-map loader in `MapScreen.kt`. A loading indicator
    should always sit on this backdrop, not naked over content.
  - **Inline / small (menu or sheet header)**: bare `LoadingIndicator` at
    ~28 dp next to the title — see the coverage points sheet in
    `SignalCoverageScreen.kt`.
  - **Pull-to-refresh**: `PullToRefreshBox` with `indicator =
    { PullToRefreshDefaults.LoadingIndicator(state, isRefreshing, ...) }` —
    see the Me panel in `ConnectScreen.kt`.
- **"Snake-like" wavy bar** — `androidx.compose.material3.LinearWavyProgressIndicator`
  for indeterminate line-style progress (e.g. under a top bar while scanning) —
  see `ConnectScreen.kt`. Use it instead of `LinearProgressIndicator` wherever a
  horizontal loading bar is needed.
