# Digital Volume Control

[![Build](https://github.com/JermaineJamesDev/DigitalVolumeControl/actions/workflows/build.yml/badge.svg)](https://github.com/JermaineJamesDev/DigitalVolumeControl/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
![Min SDK](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)

A floating on-screen volume control for Android. A small bubble sits on top of
your other apps; tap it to adjust media, ring, notification, alarm and system
volume without touching the hardware buttons.

Useful when the volume buttons are broken, hard to reach, or awkward to use,
and for anyone who prefers an on-screen control.

## Features

- **Floating bubble** that stays above other apps. Drag it anywhere and it
  snaps to the nearest screen edge.
- **Two control styles**: vertical sliders in the style of the Android system
  volume panel, or compact up/down buttons with a level bar.
- **Per-stream control** of Media, Ring, Notification, Alarm and System volume,
  each with its own mute toggle. Pick which streams appear.
- **Adjustable opacity** so the widget stays out of the way.
- **Auto-collapse** after a few seconds without use.
- **Material You**: follows your wallpaper colours on Android 12+ and supports
  light and dark themes.
- **Accessible**: the bubble, sliders and buttons work with TalkBack.
- **Private**: no ads, no analytics, and no internet permission.

## Download

Download the latest signed APK from the
[Releases](https://github.com/JermaineJamesDev/DigitalVolumeControl/releases)
page and open it on your device. You may need to allow installs from your
browser or file manager.

Requires **Android 11 (API 30)** or newer.

## Permissions

| Permission | Why it is needed |
| --- | --- |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | Draws the floating bubble and volume panel above other apps. You grant this from the app's first screen. |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`) | Keeps the widget alive while you use other apps. |
| Notifications (`POST_NOTIFICATIONS`, Android 13+) | Shows the ongoing "Volume Control Active" notification, which has a Stop action. |

The app does not request internet, storage, location or any other access.

## Building from source

### Requirements

- JDK 17 or newer
- Android SDK with platform 36 (Android Studio installs this for you)

### Build a debug APK

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. To install it on a
connected device or emulator:

```bash
./gradlew installDebug
```

### Run checks

```bash
./gradlew lintDebug testDebugUnitTest
```

### Build a signed release APK

Release signing is read from a `keystore.properties` file in the project root,
which is git-ignored.

1. Create a keystore if you do not have one:

   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias release
   ```

2. Copy `keystore.properties.example` to `keystore.properties` and fill in the
   values.
3. Build:

   ```bash
   ./gradlew assembleRelease
   ```

Without `keystore.properties`, `assembleRelease` still succeeds but produces an
unsigned APK.

## Continuous integration

The [Build workflow](.github/workflows/build.yml) runs on every push to `main`,
on pull requests, and on demand:

- Runs lint and unit tests, then builds a debug APK.
- Uploads the debug APK as a workflow artifact (open the run in the Actions tab
  and download `digital-volume-control-debug`).

### Publishing a release

Pushing a tag that starts with `v` also builds a signed release APK and
attaches it to a GitHub Release with auto-generated release notes.

One-time setup: add these repository secrets under
**Settings > Secrets and variables > Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Your `release.jks`, base64-encoded |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key alias |
| `KEY_PASSWORD` | The key password |

To base64-encode the keystore:

```bash
base64 -w 0 release.jks
```

On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```

For each release:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and commit.
2. Tag and push:

   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

Keep your keystore backed up somewhere safe. Android only installs an update
if it is signed with the same key as the installed version.

## Project structure

```
app/src/main/java/com/jpdjdev/digitalvolumecontrol/
├── MainActivity.kt          Settings screen (permission, on/off, appearance, streams)
├── VolumeOverlayService.kt  Foreground service hosting the floating widget
├── WidgetPreferences.kt     SharedPreferences wrapper and setting enums
└── ui/
    ├── Theme.kt             Material 3 theme with dynamic colour
    └── StreamIcons.kt       Icons for each audio stream
```

The UI is written entirely in Jetpack Compose, including the overlay, which is
a `ComposeView` added through `WindowManager` from the foreground service.

## Contributing

Bug reports, ideas and pull requests are welcome. For larger changes, please
open an issue first to discuss the approach.

Before opening a pull request, make sure this passes:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## License

```
Copyright 2025 JermaineJamesDev

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
