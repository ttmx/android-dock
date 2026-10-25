<img src="assets/icon.svg" width="64" height="64" alt="">

# android-dock

An iOS StandBy–style dock for Android, as a screen saver. Put your phone on a wireless charger and it shows
a full-screen, OLED-black display: a big clock with the essentials beside it, and controls one tap away.

Always visible:

- **Clock & date**, plus weather from Home Assistant
- **Phone battery**: level, charging state, and time until full
- **Car battery**: level, charging state, time until full, and range, from any Home Assistant car integration
- **Notifications**: the latest ones that fit; tap to open (unlocks first), swipe to dismiss

Tap anywhere to reveal the controls (they fade back after 20 seconds):

- **Home controls**: AC (on/off and target temperature), blinds (up/stop/down with live position),
  plus any lights, switches, scenes, scripts, or locks you choose
- **Now playing**: album art and play/pause/skip for whatever is playing

It also has a red **night mode** that kicks in automatically in a dark room, a burn-in shift that
nudges the layout every minute. Android starts and stops it with the charger, so nothing runs in the
background and there's no permanent notification.

## Install

Grab the APK from the [latest release](https://github.com/ttmx/android-dock/releases/latest). The app keeps
itself up to date from GitHub releases (allow "Install updates" in its setup list). Android asks you to
confirm the first update, and after that they install silently. Updates never install while the dock is on screen.

## Setup

1. Install the app (above), or build it yourself with `./gradlew :app:installDebug`.
2. Open **Dock** and go through the *Setup* list. **Screen saver** opens Android's screen saver settings:
   pick Dock, set "When to start" to "While charging", and on Pixels turn off **Low light mode** (it swaps any
   screen saver for Google's clock in the dark; Dock has its own red night mode). Dock stays dark on cables unless you turn off
   "Wireless chargers only". If Android says notification access is restricted, open Dock's App info,
   tap ⋮ → **Allow restricted settings**, and try again.
3. Enter your Home Assistant URL and a long-lived access token (HA → Profile → Security), then tap
   **Test & load entities**.
4. Pick your entities with the list buttons. The picker searches everything and puts likely
   matches first, so it works whatever car or climate integration you use. Car "time until charged"
   can be minutes, hours, or a "charge complete at" timestamp.

## Developing without a real Home Assistant

`tools/mock_ha.py` is a tiny fake HA (WebSocket + REST) with a charging car, AC, blinds, and a lamp:

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
