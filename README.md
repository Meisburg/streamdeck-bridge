
# Stream Deck Bridge

**Your AI coding agent, one keypress away.**

Turn a physical Stream Deck into a command pad for AI agents on Android. Approve changes, trigger skills, review code, and steer your workflow without typing every prompt.

**Platform:** Android only. There is no iOS version, and none is in progress.

**Hardware:** I've only tested this against the MK.2 (20GAA9902). Other models may work as-is, since they share the same HID protocol, but I have not verified them. If you get one working, a pull request is welcome.

<p align="center">
  <img src="screenshots/hero.png" alt="SD Bridge connecting a physical Stream Deck to an Android phone" width="340">
</p>

## Get started

1. [Download the APK](https://github.com/Meisburg/streamdeck-bridge/releases).
2. Open SD Bridge, tap **Deck setup**, then **Enable typing service**.
3. Enable SD Bridge in Android Accessibility settings.
4. Connect your Stream Deck via USB and grant permission.
5. Open your AI agent, focus its input field, and press a key.

**Requires:** Android 9+, USB host support, a compatible Stream Deck, and a USB adapter or cable.

## What can you do?

**Plan. Build. Review. Ship.**

The 15 customizable keys cover the AI coding workflow, from turning ideas into specs and tickets to implementation, code review, and pull requests.

| Plan | Build | Improve |
|---|---|---|
| Recommend | Write a spec | Undo |
| Approve | Create tickets | Hand off |
| Explain | Implement | Diagnose |
| Interview | Review code | Retrospect |
| Challenge | Prepare a PR | Improve architecture |

Every key sends custom text or a slash command supported by your agent. The default slash commands use [Matt Pocock's skills](https://github.com/mattpocock/skills).

## Privacy

No networking, telemetry, or analytics. Commands are stored locally. Accessibility access locates the focused text field and enters your configured text.

## Troubleshooting

* **Nothing types:** Reenable the SD Bridge accessibility service.
* **Blank key labels:** Open **Deck setup** and tap **Re-arm keys**.
* **Build fails:** Run `./build.sh`, which pins JDK 21.

## How it works

SD Bridge communicates directly with the Stream Deck over USB and types into the focused Android text field. Submission via Enter or a send button works where supported.

Under the hood: the app claims the deck's HID interface directly through Android's USB host API, with no root and no Elgato drivers. Key images are written to the panel as HID feature reports; key presses arrive as HID input reports, one byte per key. Getting from there to text in an arbitrary app is the harder half, and the engineering notes below cover why.

## Engineering notes

The parts that were not obvious, kept here because they cost time to discover.

**No root, and no vendor software.** Android's `UsbManager` exposes the deck as a HID device. `openDevice()` plus `claimInterface()` is enough to drive it, provided no other package wins the enumeration race first.

**The deck stays mute until it receives a feature write.** It does not report key presses on a fresh connection even though it enumerates cleanly. The first control transfer that succeeds on the feature path is what enables input reporting. A device that lights up but never reports a press is a device that never got that write.

**Termux cannot be typed into by accessibility.** `com.termux.view.TerminalView` has no editable node, so `ACTION_SET_TEXT` does nothing there. Termux accepts keystrokes only from an input method. The app therefore ships a minimal IME (`DeckKeyboardService`) that renders no UI and exists solely to hold the `InputConnection`, which lets text arrive at the prompt as if typed. When the IME is not active the app falls back to Termux's `RUN_COMMAND` shell API, which works but is invisible to the user.

**Chat apps need a different path than terminal apps.** For apps with a real `EditText`, `ACTION_SET_TEXT` followed by `ACTION_IME_ENTER` is reliable. Reflective `KeyEvent` injection is not: it frequently lands in the wrong field or duplicates the submit. The two paths are deliberate, not one fallback for the other.

**Key images must be rotated 180 degrees** before they render correctly. Without it the panel accepts the image and displays nothing.

**The USB link can degrade to permanent silence.** When the system `com.android.mtp` service wins the device, the app can still get a successful `openDevice()` and `claimInterface()` while every `controlTransfer` returns -1. The symptom looks like a dead device. Third-party handlers can be disabled to reduce the contention; MTP is a system priv-app and cannot be removed without root. A powered hub changes the enumeration path and is the practical workaround.

**Reader and writer need separate single-thread dispatchers.** Sharing one dispatcher lets the input reader starve the writer, which presents as intermittent image corruption rather than as a threading problem.

**Key protocol and environment details** live in the skill referenced in the commit history, and in `PHONE-CHANGES.md` for the phone-side setup.

## Built by

Jon Meisburg — AI Product Engineer, Seattle. [LinkedIn](https://www.linkedin.com/in/jonmeisburg) · [GitHub](https://github.com/Meisburg)

Reverse engineered from Elgato's own macOS package to learn the HID protocol, then rebuilt as a native Android implementation with no vendor dependency.

## Contributing

Have a different Stream Deck model? Pull requests adding compatibility are welcome.

## License

MIT. Not affiliated with or endorsed by Elgato.
