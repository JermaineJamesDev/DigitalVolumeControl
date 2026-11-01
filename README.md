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
- **Adjustable bubble opacity** so the bubble stays out of the way. The panel
  always opens fully visible.
- **Closes when you are done**: tap outside the panel, or leave it for a few
  seconds. Settings changes apply to the widget immediately.
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

## Setup

The bubble runs as an Android accessibility service. This keeps it on screen
for as long as the service is enabled, with no ongoing notification.

1. On Xiaomi, Redmi and POCO phones, turn on **Autostart** for the app first
   (the app has a shortcut). Without it the phone may refuse to start the
   service, and the switch turns on and then fails.
2. Open the app and tap **Open accessibility settings**, then find
   **Digital Volume Control** (it may be under *Downloaded apps* or
   *Installed apps*).
3. Turn the service on.
4. On Android 13 and newer, apps installed from an APK are blocked at this
   step. Tap the switch once so Android shows the "Restricted setting"
   message, then open the app's **App info** page, tap the three-dot menu,
   choose **Allow restricted settings**, and turn the service on again. The
   menu item only appears after that first refused attempt.

Use the switch in the app to hide or show the bubble. To turn the service off
completely, switch it off in Accessibility settings.

### Keeping it running on Xiaomi and similar phones

Some phones, including Xiaomi, Redmi and POCO, close background apps to save
battery and can switch the service off with them. On the app's **App info**
page, turn on **Autostart** and set **Battery saver** to **No restrictions**.
On Xiaomi devices the app has a shortcut straight to the Autostart list. Also
lock the app in Recent apps so clearing recents does not close it.

If Accessibility settings shows an error or "Not working" for the service,
Android lost it in the background. The app detects this, shows the reason
Android recorded for the last stop, and asks you to turn the service off and
on again.

## Permissions

| Permission | Why it is needed |
| --- | --- |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Lets the app draw the floating bubble and volume panel above other apps. You enable it in Accessibility settings. |

The accessibility service requests no accessibility events and cannot read
window content. It only draws the widget; it does not see what is on screen,
record what you type, or perform actions for you.

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
├── MainActivity.kt          Settings screen (setup, show/hide, appearance, streams)
├── VolumeOverlayService.kt  Accessibility service hosting the floating widget
├── WidgetPreferences.kt     SharedPreferences wrapper and setting enums
└── ui/
    ├── Theme.kt             Material 3 theme with dynamic colour
    └── StreamIcons.kt       Icons for each audio stream
```

The UI is written entirely in Jetpack Compose, including the overlay, which is
a `ComposeView` added through `WindowManager` as an accessibility overlay.

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
