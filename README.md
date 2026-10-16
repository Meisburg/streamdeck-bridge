# SD Bridge

Control your AI agent from your phone with a physical Stream Deck.

Press a key to send a prompt or slash command to any focused Android text field. No typing, no app specific integration, no cloud service.

<p align="center">
  <img src="screenshots/hero.png" alt="SD Bridge running on a Stream Deck and Android phone" width="340">
</p>

## Get started

1. [Download the APK](https://github.com/Meisburg/streamdeck-bridge/releases).
2. Open SD Bridge, tap **Deck setup**, then **Enable typing service**.
3. Enable SD Bridge in Android Accessibility settings.
4. Connect your Stream Deck via USB and grant permission.
5. Open your AI agent, tap its input field, and press a key.

**Requirements:** Android 9 or newer, USB host support, an Elgato Stream Deck, and a USB adapter or cable.

## What can it do?

The 15 default keys cover a complete AI coding workflow:

| Plan | Build | Improve |
|---|---|---|
| Recommend | Write a spec | Undo |
| Approve | Create tickets | Hand off |
| Explain | Implement | Diagnose |
| Ask Matt | Review code | Retrospect |
| Grill | Prepare a PR | Improve architecture |

Every key is customizable. Send your own prompts, or slash commands your agent supports.

Slash commands need the matching skill installed. The defaults use [Matt Pocock's skills](https://github.com/mattpocock/skills).

See [The 15 default commands](#the-15-default-commands) for the exact text behind each key.

## Privacy

- No networking, telemetry, or analytics.
- Commands are stored locally.
- Accessibility access is used to find the focused field and enter your configured text.

[The accessibility disclosure](#the-accessibility-disclosure) covers exactly what it can and cannot see.

## Troubleshooting

- **Nothing types:** Re-enable the SD Bridge accessibility service.
- **Blank key labels:** Open **Deck setup** and tap **Re-arm keys**.
- **Build fails:** Run `./build.sh`, which pins JDK 21.

## How it works

SD Bridge talks to the Stream Deck directly over USB and types into the focused Android text field. It supports configurable submission behavior, including Enter or a send button where the app allows it.

See the source code for implementation details, or [How the deck protocol works](#how-the-deck-protocol-works) for the USB details.

## License

MIT. Not affiliated with or endorsed by Elgato.

---

Everything below is reference. You do not need it to get started.

[The accessibility disclosure](#the-accessibility-disclosure) · [Why this exists](#why-this-exists) · [Limitations](#known-limitations) · [Deck protocol](#how-the-deck-protocol-works) · [Repository layout](#repository-layout) · [Trademark](#license-and-trademark)

---

## The 15 default commands

<p align="center">
  <img src="screenshots/app.png" alt="SD Bridge: a 15-key grid of agent commands, three rows of five" width="380">
</p>

The defaults are not a random list of macros. They are **the phases of building a feature, in the order the phases happen**, laid out so the physical position of a key tells you where you are.

Row 1 aligns on what to build. Row 2 specifies, tickets, implements, reviews, ships. Row 3 is the row of things that are *not* part of the build: recover, pause, repair, reflect, improve.

```
Row 1   Recommend   Approved   Explain     Ask Matt   Grill
Row 2   To spec     To tickets Implement   Code review  PR
Row 3   Undo        Handoff    Diagnose    Retro      Improve
```

Every key is editable. These are a starting point, and the whole point of the app is that you replace them with your own.

### Row 1: align on what to build

| Key | Sends | What it is for |
| :--- | :--- | :--- |
| **Recommend** | `what do you recommend?` | Forces a decision out of the agent instead of a menu. |
| **Approved** | `approved` | The most-pressed key on the deck. Says yes and moves on. |
| **Explain** | `explain as simply and succinctly as possible` | Re-pitches something that did not land. |
| **Ask Matt** | `/ask-matt` | Routes to the right skill in the set below, when you are not sure which one you want. |
| **Grill** | `/grill-with-docs` | Relentless interview about a plan or design, which also builds the project's glossary and ADRs as it goes. |

`Ask Matt` and `Grill` are the two halves of the same idea: `ask-matt` is the router, and `grill-with-docs` is the one you end up in most often. The point of a grilling session is to close the gap between what you think you asked for and what the agent understood, before any code exists.

### Row 2: spec, ticket, build, review, ship

| Key | Sends | What it is for |
| :--- | :--- | :--- |
| **To spec** | `/to-spec` | Turns the conversation you just had into a written spec. No interview, just synthesis. |
| **To tickets** | `/to-tickets` | Breaks that spec into tracer-bullet tickets, each declaring what blocks it. |
| **Implement** | `/implement-spec` | Builds a whole spec, working the tickets as a task graph. |
| **Code review** | `/code-review` | Two-axis review of the diff: does it follow the repo's standards, and does it match the spec. |
| **PR** | `/pr` | Shapes the pull request body: the smallest visual that makes the change clear, before/after evidence, and a merge-danger call. |

> **Keep Row 1 in one unbroken context window.** `ask-matt`, `grill-with-docs`, `to-spec` and `to-tickets` are meant to build on the same thinking. Do not compact or clear the conversation between them. That is why those four sit together on row 1.

### Row 3: leave the build

| Key | Sends | What it is for |
| :--- | :--- | :--- |
| **Undo** | `undo that last change` | Says "that worked, and I did not want that." Not a `git revert`; it is a message to the agent. |
| **Handoff** | `/handoff` | Compacts the conversation so a fresh agent can continue. For running out of context, or getting pulled away mid-task. |
| **Diagnose** | `/diagnosing-bugs` | Disciplined debugging loop for the hard ones: build a loop that goes red on this bug, then fix it. |
| **Retro** | `/retro` | After a build, suggests changes to the *agent's environment*: navigation, checks, standards, tooling. |
| **Improve** | `/improve-codebase-architecture` | Surveys the codebase for deepening opportunities. Worth running every few days. |

Every key in rows 1 and 2 ends by *changing something*. Row 3 is the row that can take it back, pause it, repair it, and make the next build start from a better place.

### The skills behind the slash keys

All slash keys come from [mattpocock/skills](https://github.com/mattpocock/skills), a set of small composable engineering skills. Install that set in your agent of choice; the README there covers it. The specific ones this deck uses:

`ask-matt` · `grill-with-docs` · `to-spec` · `to-tickets` · `implement-spec` · `code-review` · `pr` · `handoff` · `diagnosing-bugs` · `retro` · `improve-codebase-architecture`

Two of these are deliberately **not** on the deck: `grilling` and `codebase-design` can be reached for automatically by the agent, so they do not need a physical key.

### Making it yours

The defaults are one workflow. Yours will differ.

Tap any key to edit it. Long-press for Edit, Clear, or Send-as-is. Two fields matter:

- Button name: what the key face shows. Keep it short. It auto-sizes to fit, but shorter reads better at a glance.
- Text to send: the literal text, or the slash command.

If most of your keys end up being plain phrases you type a lot ("looks good, continue", "check that against the spec"), that is the app working as intended.

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

**Credit where it is due:** these findings came from reverse-engineering the
official desktop app with [rea](https://github.com/morluto/rea), an open-source
toolkit for inspecting how a closed-source app talks to its hardware. Everything
below is the result of that work, checked against a live MK.2 (PID `0x006D`) rather
than taken on faith. If you are trying to drive this hardware from a platform
nobody supports yet, start there.

- **The deck is not a keyboard.** Android has no driver for it, and it will not be
  treated as a standard HID input device. Its input endpoint cannot be read with
  plain blocking reads on Android; they return `-1` forever.
- **The deck must be woken** with a feature report before it sends key reports.
- **Key payload lands one byte per key at offset `+0x04`** of a 512-byte input
  report.
- **Key images are rotated 180 degrees** relative to what you send. This was
  carried as an unverified assumption for a while, because no image had ever
  reached the panel, because the writes were silently being dropped for an
  unrelated reason. The first face that did appear was upside down, which settled it.
- **MTP fights you for the device.** The `com.android.mtp` stack grabs the deck via
  a wildcard USB filter. The app detaches it, which is why the "which app owns this
  USB device" question matters.
- **Two dispatchers are required:** one to keep the USB link alive, one to re-arm
  after failures.

The app handles all of this; this section exists for people modifying it.

---

## Known limitations

Things that do not work yet.

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
- **If typing stops working, the app tells you.** A resident service watches the
  accessibility binding and raises a notification with a tap straight into the
  setting that fixes it. It deliberately does not steal focus to do this: an earlier
  version did, and it threw you out of Settings faster than you could reach the
  toggle.
- **The deck's USB link degrades under load**, so a key press can occasionally go
  silent until the app re-arms. `Deck setup` has a **Re-arm keys** button, and it
  also re-arms itself after a period of quiet. If it happens often, the cable is
  the usual culprit.

---

## Repository layout

```
app/src/main/java/com/hermes/deckbridge/
  MainActivity.kt              UI: the 15-key grid, editor, setup screen
  CommandStore.kt              Command model + persistence (15 fixed slots)
  CommandReceiver.kt           ADB/broadcast entry point (UID-guarded)
  DeckAccessibilityService.kt  The core: targeting, typing, submitting
  DeckTransport.kt             USB HID: bring-up, read loop, brightness, re-arm
  DeckProtocol.kt              The HID protocol itself: reports, key images, wake
  KeyImage.kt                  Draws each key face as a JPEG for the hardware
  DeckResidentService.kt       Keeps the process alive; hosts the watchdog
  DeckSettings.kt              Persisted deck settings (brightness, rotation)
  DeckKeyboardService.kt       Optional IME path for terminal apps
  ImeSwapper.kt                Swaps the active IME per press
  BootReceiver.kt              Re-arms on boot and after an update
  SendConfirm.kt               The post-send notification
  MacroStore.kt                Key-to-action mapping, persisted as JSON
  UsbProbe.kt                  Diagnostic: reports what the USB stack actually does
```

The last two are worth knowing about even if you never touch them. `MacroStore`
is the mapping layer between a key and its action, stored as editable JSON in the
app's private directory, which is what makes the deck scriptable from a
workstation. `UsbProbe` exists because `controlTransfer` calls were timing out at
exactly the read timeout, and a guess about why would have been wrong.

---

## Why this exists

I'm constantly on the go, and I wanted to drive my agents from my phone rather
than only read them. Voice gets me most of the way, but there are moments where I can't talk out
loud. A meeting, a sleeping kid, a coffee shop. In those moments,
typing every prompt on a phone keyboard is the thing that stops me steering at
all.

I had Stream Decks sitting on my desk. I wondered whether I could press a key and
have canned text land in my agent.

Elgato ships Windows and Mac software only. No Android. So this is a rebuild of
what the desktop app does, for a phone.

Getting there meant reverse-engineering the HID protocol, which
[rea](https://github.com/morluto/rea) made possible: an open-source toolkit for
taking apart the official software and working out how it talks to the hardware.
The findings are written up in [How the deck protocol
works](#how-the-deck-protocol-works) so the next person does not have to
rediscover them.

---

## License and trademark

MIT. See [LICENSE](LICENSE).

The icon is an original mark: a bridge span over five key-lights, drawn for this
project. It is yours to keep or replace under the MIT license.

This project is **not affiliated with, endorsed by, or sponsored by Elgato.** "Elgato"
and "Stream Deck" are trademarks of Elgato Systems, and they are used here only to
say what hardware this works with, which is normal and permitted. No Elgato artwork
is included in this repository.

If you fork this, you can rename and re-icon it freely; nothing in the code depends
on the name or the mark.
