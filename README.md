<img src="assets/icon.svg" width="64" height="64" alt="">

# android-dock

A StandBy-style screen saver for Android that runs while the phone charges.

It shows:

- Clock, date, and optionally weather from Home Assistant
- Phone battery, with time until full
- Car battery, with time until full or range, from any Home Assistant car integration
- Recent notifications (tap to open, swipe to dismiss)

Tap the screen for controls, which hide again after 20 seconds:

- AC, blinds, and any lights, switches, scenes, scripts, or locks you add
- Media playback

The screen turns red in a dark room and shifts a few pixels every minute against burn-in.

## Install

Download the APK from the [latest release](https://github.com/ttmx/android-dock/releases/latest). It updates
itself from GitHub releases once you allow "Install updates" in its setup list. Android asks you to confirm
the first update; later ones install silently, never while the dock is on screen.

## Setup

1. Install the app (above), or build it yourself with `./gradlew :app:installDebug`.
2. Open **Dock** and go through the *Setup* list. In Android's screen saver settings, pick Dock and set
   "When to start" to "While charging". On Pixels, also turn off **Low light mode**, which replaces any screen
   saver with Google's clock in the dark. Dock only runs on wireless chargers unless you turn off
   "Wireless chargers only". If notification access is restricted, open Dock's App info, tap
   ⋮ → **Allow restricted settings**, and try again.
3. Enter your Home Assistant URL and a long-lived access token (HA → Profile → Security), then tap
   **Test & load entities**.
4. Pick your entities with the list buttons. The picker searches everything and puts likely
   matches first, so it works whatever car or climate integration you use. Car "time until charged"
   can be minutes, hours, or a "charge complete at" timestamp.

## Developing without a real Home Assistant

`tools/mock_ha.py` is a tiny fake HA (WebSocket + REST) with a charging car, an AC with adaptive control, blinds, and a lamp:

```sh
pip install websockets
python3 tools/mock_ha.py 8124
adb reverse tcp:8124 tcp:8124   # then use http://localhost:8124 and any token in the app
```

To fake a wireless charger: `adb shell dumpsys battery set wireless 1` (undo with `adb shell dumpsys battery reset`).
To see the screen saver, tap Preview in Android's screen saver settings, or let the screen time out while
charging (with Developer options → Stay awake turned off).

## Releases

Every push to `main` runs [CI](.github/workflows/ci.yml): it builds, lints, and publishes a signed release
(`v0.<n>`, asset `android-dock-<n>.apk`) that installed apps pick up automatically. Pull requests only build and lint.

Signing uses one release key, so updates install over the previous version. CI reads it from the
`DOCK_KEYSTORE_BASE64`, `DOCK_KEYSTORE_PASSWORD`, `DOCK_KEY_ALIAS` and `DOCK_KEY_PASSWORD` secrets. Locally, Gradle
reads `~/.android-dock-signing/keystore.properties` (or the path in `DOCK_KEYSTORE_PROPERTIES`) and falls
back to the debug key when neither exists. A fork needs its own key, and has to change `UPDATE_REPO` in
`app/build.gradle.kts` to update from its own releases.

## Credits

The clock uses [Nunito Sans](https://github.com/Fonthausen/NunitoSans) Black, cut down to digits, under the
[SIL Open Font License](third_party/fonts/NunitoSans-OFL.txt).
