# Pinion Smart.Shift gear field for Hammerhead Karoo

A Karoo extension (`io.hammerhead:karoo-ext`) that connects to a Pinion Smart.Shift gearbox
(c1.12i and siblings) over BLE and provides two data fields: **Pinion Gear** and **Pinion Battery** (%), both
standard numeric fields drawn by Karoo.

## Status

Builds, and the protocol unit tests pass (first build 2026-10-07: Gradle 9.1.0, AGP 8.13.2, Kotlin 2.3.0,
Android Studio's bundled JDK 25). Works on a Karoo 2 against the fake gearbox `tools/fake_pinion.py` (Raspberry Pi, Bumble): connect, shifts,
battery, disconnect alert and reconnect all checked 2026-10-07. **Not yet run against a real gearbox.**

## BLE protocol

Source: Tim Angus's reverse engineering, <https://github.com/timangus/garmin-connectiq-pinion-barrel>
(the library behind <https://github.com/timangus/pinion-garmin-settings>), blog post
<https://timang.us/2025-08-09-pinion-garmin-settings/>. Mirrored in `PinionProtocol.kt`.

| What | UUID | Properties |
|---|---|---|
| Service (also in the advertisement) | `00000000-33d2-4f94-9ee4-9312b3660005` | |
| Current gear | `00000001-33d2-4f94-9ee4-9312b3660005` | notify |
| Request | `0000000d-33d2-4f94-9ee4-9312b3660005` | write with response |
| Response | `0000000e-33d2-4f94-9ee4-9312b3660005` | indicate |

- **Gear notification:** byte 0 = gear, 1-based. Sent on change only, so the start value is read.
- **Requests** are CANopen SDO style. Read: `01 <len> <a0 a1 a2>`. Reply (indication on Response):
  `02 <len> <a0 a1 a2> <value, little endian>`. First byte `00` = error. (Write is `03`, ack `04 ff <addr>`; unused here.)
- **Parameters** (address bytes as sent, length): current gear `01 61 02` (1), battery `64 61 01` (2, hundredths of a
  percent), number of gears `00 25 00` (1), serial `18 10 04` (4).
- Connection sequence: connect, discover, enable indications on Response, enable notifications on Gear, then read.
  One GATT operation at a time.
- **Advertising:** only after the rear shift button is held for 3 seconds. It is not bonding. When a
  connection drops the gearbox stops advertising, so reconnecting always needs the button again;
  the client just scans until that happens and the field shows "Hold shift 3s".

Open questions to settle on the real hardware:
- How long the advertising window lasts after the button hold.
- Whether the BLE address is stable (assumed yes: after the first connection the scan filters on the saved address).
- Whether the gearbox ever demands an encrypted link (would show as GATT status 5, 8 or 15 in the log).
- 2.4 GHz interference caused dropouts for the Garmin author (an ANT+ heart rate strap).

## Layout

- `PinionProtocol.kt`: UUIDs, request encoding, reply decoding. Pure Kotlin, covered by `PinionProtocolTest`.
- `PinionBleClient.kt`: scan, connect, subscribe, poll battery every 60 s, retry forever. Publishes `StateFlow<PinionState>`. Process-wide singleton. Runs only while the active ride profile contains a Pinion field (`ActiveRideProfile` event, handled in the service) or the status activity is open, and for 2 minutes after. Field streams are no use for this: Karoo keeps them running whatever profile is open.
- `extension/PinionExtensionService.kt`: the `KarooExtension` service; owns the client's lifetime, requests the BT radio from Karoo, raises an in-ride alert on disconnect.
- `extension/PinionDataType.kt`: one class for both fields; it only streams a value, Karoo draws the field.
- `MainActivity.kt`: requests Bluetooth/location runtime permissions, shows live status, "Forget gearbox".
- `res/xml/extension_info.xml`: extension id `pinion`, type ids `gear` and `battery`. These must match the Kotlin constants.

Karoo facts that shaped the code (the layout ones found on a Karoo 2, 2026-10-07):
- The manifest action is `io.hammerhead.karooext.KAROO_EXTENSION`.
- Karoo 2 is Android 8 (legacy BT permissions + location), Karoo 3 is Android 13.
- **Why the fields are plain numeric ones.** A graphical (RemoteViews) gear field was built first, to show
  "Hold shift 3s" in the field, and dropped the same day because it could not be made to look native:
  - Karoo's field font is not available to RemoteViews (and `sans-serif-condensed` has no effect on the Karoo 2).
  - With `showHeader = true` the view is pushed down by the header height but keeps the full field height
    (`ViewConfig.viewSize`), so its bottom is clipped; under a two-line title the number does not fit.
  - RemoteViews on Android 8 rejects `TextView.setGravity`; an exception while applying the view kills the field
    until the ride page is reopened, and is logged under tag `HHApp` ("Extensions: Error in view"), not `Pinion`.
  - `ViewEmitter.updateView` drops updates faster than about 1 Hz.
  With `graphical="false"` and no `startView`, Karoo draws title and number itself and shows "Searching..."
  while disconnected. The in-ride alert carries the "hold the button" instruction instead.
- Karoo wraps a field title longer than about 9-10 characters in a half-width field ("MAX POWER" fits,
  "PINION GEAR" wraps). It also caches a field's title in the profile: a changed `displayName` only shows after
  the field is removed and added again. Switching `graphical` took effect without re-adding.
- Screenshots for checking layout: `adb exec-out screencap -p > shot.png`.

## Build and install

1. Install Android Studio (bundles the JDK and SDK) and open this folder.
2. karoo-ext is served from GitHub Packages, which needs a token even for public packages. Create a classic
   personal access token with the `read:packages` scope and put it in `%USERPROFILE%\.gradle\gradle.properties`:
   ```
   gpr.user=<github username>
   gpr.key=<token>
   ```
3. Build and test: `.\gradlew.bat testDebugUnitTest assembleDebug`
4. Install on the Karoo (developer options + USB debugging on):
   `adb install -r app\build\outputs\apk\debug\app-debug.apk`
5. On the Karoo open **Pinion Smart.Shift** once and grant the permission, add the **Pinion Gear** field to a
   ride profile, then hold the rear shift button for 3 seconds.

Debug log: `adb logcat -s Pinion` (every Timber line uses that one tag; requests and replies are logged as hex).
