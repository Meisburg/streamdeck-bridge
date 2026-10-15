# SD Bridge (Stream Deck Bridge)

Turn an Elgato Stream Deck into a macro pad for your **Android phone**. Press a
physical deck key, and pre-written text lands in whatever app is focused: a chat
app, an AI agent, a notes field, anywhere you'd otherwise type.

Built for people who want one-tap commands without unlocking, hunting for an app,
and typing on a phone keyboard.

<p align="center">
  <em>15 on-screen keys, mirrored to 15 physical deck keys.</em>
</p>

---

## What it does

- **Two surfaces, one set of commands.** A 15-key tap grid in the app (works with
  no hardware at all, from a phone in your pocket), and the same 15 commands bound
  to the physical keys of a USB Stream Deck.
- **Tap a key to edit it.** No long-press-to-edit dance, no separate config screen.
- **Long-press a key** for Edit / Clear / Send-as-is.
- **Send confirmation.** After every send the app posts a notification telling you
  what it typed and whether it landed, because the app backgrounds itself to send.
- **Brightness control** for the deck backlight, to save battery.
- **No root.** No ADB required for normal use. No accounts, no network calls, no
  analytics.

---

## Requirements

- An Android phone with **USB host support** (OTG). Most modern phones have it; if
  yours doesn't, the tap grid still works perfectly.
- An **Elgato Stream Deck** (MK.2 and the 15-key models are what this was developed
  and tested against).
- Android 9.0 (API 29) or newer.
- A USB-C to USB-A adapter or cable that fits your phone, ideally with PD
  passthrough if you want to charge while the deck is attached.

---

## Install

Build it yourself; there is no Play Store listing.

```bash
git clone https://github.com/Meisburg/streamdeck-mobile
cd streamdeck-mobile
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and hit Run.

### First-run setup

1. Open **SD Bridge**.
2. Tap **Deck setup**, then **Enable typing service**.
3. In the Android Settings screen that opens, find **SD Bridge** and turn it on.
   Android will warn you about what an accessibility service can do. See the
   disclosure below for exactly what this one does.
4. Plug in your Stream Deck. Android asks for USB permission: allow it.

That's it. The binding persists across reboots.

---

## Daily use

There is nothing to press before a send.

1. Open the app you want text to land in (a chat app, your agent, anything).
2. Tap its text field.
3. Press a key, on the phone screen or the physical deck.

If labels ever go blank after unplugging and replugging, tap **Deck setup** and use
**Re-arm keys**. It also re-arms itself automatically after a period of silence.

---

## The accessibility disclosure

This app asks for an **accessibility service**, which is the same permission
screen readers use. That is genuinely powerful, so here is exactly what this app
does with it, and what it does not do.

**It does:**
- Read the current screen to find the focused text field.
- Insert the text you pre-configured into that field.
- Press Enter, or tap a send button, if you enabled that per command.

**It does not:**
- Send anything anywhere. There is **no networking code in this app at all**. No
  servers, no telemetry, no analytics, no crash reporting.
- Record, store, or log what is on your screen. Text is read to find a target and
  discarded.
- Talk to accounts or credentials of any kind.

The only thing it ever types is text you typed into its own editor. Everything is
stored locally in the app's private data directory.

Being blunt about the tradeoff: an accessibility service that reads the screen is
a category of software you should grant deliberately. Read the source if you want
to confirm the claims above; it is a small app.

---

## Security note: the broadcast receiver

The app exposes one receiver so a computer can trigger a command over ADB:

```bash
adb shell am broadcast -a com.hermes.deckbridge.SEND \
  -n com.hermes.deckbridge/.CommandReceiver \
  --es text "hello there" \
  --es target discord
```

Because that receiver injects text into whatever is focused, it would be a
text-injection primitive if any app could call it. It is guarded by a **sender
UID check in code**: only this app's own UID, root, and the ADB shell UID are
accepted. Ordinary third-party apps are refused and logged.

A note for anyone tempted to "improve" this by moving the guard into the manifest
as an `android:permission` with `protectionLevel="signature"`: **do not**. That was
tried and measured. On a real device (Pixel 9 Pro, Android 15) a signature-level
permission is *silently dropped* for the shell UID, so `am broadcast` reports
`Broadcast completed: result=0` while the receiver never runs. `signatureOrSystem`
fails the same way; granting it with `pm grant` also fails. It works on an
emulator, which is exactly what makes it a nasty trap. The in-code check works on
hardware and is the reason it is written that way.

---

## How the deck protocol works

Documented here because it is the part that took the longest to work out, and the
next person should not have to rediscover it.

- **The deck is not a keyboard.** Android has no driver for it, and it will not be
  treated as a standard HID input device. Its input endpoint cannot be read with
  plain blocking reads on Android; they return `-1` forever.
- **The deck must be woken** with a feature report before it sends key reports.
- **Key payload lands one byte per key at offset `+0x04`** of a 512-byte input
  report.
- **Key images are rotated 180 degrees** relative to what you send.
- **MTP fights you for the device.** The `com.android.mtp` stack grabs the deck via
  a wildcard USB filter. The app detaches it, which is why the "which app owns this
  USB device" question matters.
- **Two dispatchers are required:** one to keep the USB link alive, one to re-arm
  after failures.

The app handles all of this; this section exists for people modifying it.

---

## Known limitations

Honest list, not a wish list.

- **Some apps cannot confirm a send.** Text arrives, but the app cannot always
  tell whether the send button registered, so the notification says
  `typed (submit unconfirmed)`. Discord is one of these.
- **Sends into a web-app composer can be slow.** Some apps reflow their text field
  between the moment it is located and the moment it is tapped, so the app retries.
  Budget a few seconds for those.
- **Deck key input on some phones is flaky at the USB level.** The read loop
  occasionally returns nothing and needs a re-arm, which the app does automatically.
  Swapping the cable is the usual real fix.
- **One phone port, two needs.** If the deck occupies your only USB-C port, use
  **wireless ADB** for computer-side work and keep the deck on the phone. If you
  need to charge at the same time, use a USB-C hub with PD passthrough.
- **No `configChanges` on the main activity**, so rotating the phone recreates it.
  Commands are safe; in-memory log lines are not.

---

## Repository layout

```
app/src/main/java/com/hermes/deckbridge/
  MainActivity.kt              UI: the 15-key grid, editor, setup screen
  CommandStore.kt              Command model + persistence (15 fixed slots)
  CommandReceiver.kt           ADB/broadcast entry point (UID-guarded)
  DeckAccessibilityService.kt  The core: targeting, typing, submitting
  DeckTransport.kt             USB HID: bring-up, read loop, brightness, re-arm
  DeckResidentService.kt       Keeps the process alive; hosts the watchdog
  DeckKeyboardService.kt       Optional IME path for terminal apps
  ImeSwapper.kt                Swaps the active IME per press
  BootReceiver.kt              Re-arms on boot and after an update
  SendConfirm.kt               The post-send notification
```

---

## License

MIT. See [LICENSE](LICENSE).

## A note on the icon

The launcher icon is derived from Elgato's Stream Deck application icon and is
included for personal use. **If you fork this for your own public release, replace
it with your own artwork.** "Stream Deck" and the Stream Deck logo are Elgato
trademarks, and this project is not affiliated with or endorsed by Elgato. The
icon is not covered by the MIT license above.
