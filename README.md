# pinion-karoo-extension

Show the **current gear** and **battery level** of a **Pinion Smart.Shift** gearbox on a
**Hammerhead Karoo**.

![Pinion Gear and Pinion Battery fields on a Karoo 2](docs/fields.png)

It is a Karoo extension (built on [karoo-ext](https://github.com/hammerheadnav/karoo-ext)) that connects
to the gearbox over Bluetooth and adds two data fields you can put on any ride page:

- **Pinion Gear** - the gear you are in (1 to 12, or as many as your gearbox has);
- **Pinion Battery** - the Smart.Shift battery level in percent.

They are ordinary Karoo fields, so they look and behave like the built-in ones.

> **Unofficial.** This project is not affiliated with or endorsed by Pinion, Hammerhead or anyone else.
> The Bluetooth protocol is not documented by Pinion and may change with a firmware update.
> Use it at your own risk.

## Status

**Not yet tested with a real gearbox.** The author's own Smart.Shift box currently refuses to enter
Bluetooth pairing mode, so everything so far was tested on a **Karoo 2** against a simulated gearbox
([tools/fake_pinion.py](tools/fake_pinion.py)) that speaks the protocol as documented by the Garmin
project credited below. Against the simulator, connecting, gear changes, battery, the disconnect alert and
reconnecting all work.

If you try it on a real Smart.Shift gearbox, please open an issue and say how it went, whether it worked
or not. The things only real hardware can answer are listed in [CLAUDE.md](CLAUDE.md).

The Karoo 3 is supported in the code (Android 13 permissions) but untested.

## What you need

- A Hammerhead Karoo 2 or Karoo 3.
- A Pinion gearbox with a **Bluetooth-capable** Smart.Shift box (the kind that works with the Pinion
  Smart.Shift phone app). Boxes that talk to an e-bike system over CAN have no Bluetooth.
- A computer with [Android Studio](https://developer.android.com/studio) and a USB cable, to build the
  app and install it on the Karoo.

## Install

There is no ready-made download yet, so the app is built from source.

1. **Get a GitHub token for the Karoo library.** karoo-ext is hosted on GitHub Packages, which needs a
   token even for public packages. On GitHub go to *Settings > Developer settings > Personal access
   tokens > Tokens (classic)*, generate a token with only the **read:packages** scope, and put it in
   the `gradle.properties` file of your user folder (`~/.gradle/gradle.properties`, on Windows
   `%USERPROFILE%\.gradle\gradle.properties`):

   ```
   gpr.user=<your GitHub user name>
   gpr.key=<the token>
   ```

2. **Open the project in Android Studio** and let the Gradle sync finish. Decline any offer to upgrade
   the Android Gradle Plugin.

3. **Switch on USB debugging on the Karoo:** *Settings > About*, tap *Build number* until developer mode
   is on, then *Settings > Developer options > USB debugging*. Connect the Karoo by USB and allow
   debugging when it asks.

4. **Press Run** in Android Studio with the Karoo selected as the device. The app installs and opens on
   the Karoo; allow the permission it asks for.

   From a command line the same is `./gradlew assembleDebug` followed by
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

5. **Add the fields.** Edit a ride profile on the Karoo, add a data field and pick **Pinion Gear** (and
   **Pinion Battery**) from the *Pinion Smart.Shift* section.

## Use

1. Open a ride profile that has a Pinion field. The field shows *Searching...*.
2. Put the gearbox in Bluetooth pairing mode: hold the shift button **without** the Pinion logo for three
   seconds and release it. The LED on the Smart.Shift box flashes blue.
3. The Karoo connects by itself and the field shows the gear.

Things to know:

- **The first gearbox found is remembered**, and from then on only that one is used. To pair a different
  one, open the *Pinion Smart.Shift* app on the Karoo and tap **Forget gearbox**.
- **If the connection drops, the gearbox stops advertising.** That is how the gearbox works, not something
  the Karoo can fix: hold the shift button for three seconds again. During a ride the Karoo shows an alert
  telling you so.
- **The gearbox accepts one Bluetooth connection.** Close the Pinion phone app (or switch off the phone's
  Bluetooth) or the Karoo will not find the gearbox.
- **Nothing scans unless you use the fields.** The extension only looks for the gearbox while the active
  ride profile has a Pinion field on one of its pages, so other profiles cost no battery.
- The *Pinion Smart.Shift* app on the Karoo shows the connection status, gear and battery, which helps
  when something does not connect.

## Troubleshooting

Everything the extension does is logged under one tag, including each request and reply in hex:

```
adb logcat -s Pinion
```

## The simulated gearbox

[tools/fake_pinion.py](tools/fake_pinion.py) pretends to be a Smart.Shift box, for working on the
extension without a bike nearby. It needs Linux, a spare Bluetooth adapter,
[Bumble](https://github.com/google/bumble) and root; it was written for a Raspberry Pi with a USB
Bluetooth dongle. Arrow keys shift, `p` is the pairing button, `x` drops the connection; the top of the
file lists the rest.

## How it works

The gearbox exposes one Bluetooth service. The gear is pushed as a notification whenever it changes;
battery, gear count and the rest are read with small request/reply messages (CANopen SDO style).
[CLAUDE.md](CLAUDE.md) has the UUIDs, message layout and the notes on Karoo behaviour collected while
building this, and [PinionProtocol.kt](app/src/main/kotlin/io/github/madooroy/pinionkaroo/PinionProtocol.kt)
is the same thing as code.

## Credits

- **[Tim Angus](https://github.com/timangus)** worked out the Smart.Shift Bluetooth protocol and published
  it as a Garmin Connect IQ library and app:
  [garmin-connectiq-pinion-barrel](https://github.com/timangus/garmin-connectiq-pinion-barrel) and
  [pinion-garmin-settings](https://github.com/timangus/pinion-garmin-settings), described in
  [his blog post](https://timang.us/2025-08-09-pinion-garmin-settings/). This extension is an independent
  implementation for the Karoo, written from the protocol his work documents; without it this project
  would not exist. If you ride with a Garmin, use his app.
- [karoo-ext](https://github.com/hammerheadnav/karoo-ext) by Hammerhead, the extension library (Apache 2.0).
- [Bumble](https://github.com/google/bumble) by Google, used by the simulated gearbox.

## License

[GPL-3.0](LICENSE).
