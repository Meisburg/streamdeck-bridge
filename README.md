# SD Bridge

**Control your AI agent from your phone with a physical Stream Deck.**

Press a key to send a prompt or slash command to any focused Android text field. No typing, app specific integration, or cloud service required.

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
| Interview | Review code | Retrospect |
| Challenge | Prepare a PR | Improve architecture |

Every key is customizable. Send your own prompts or slash commands supported by your agent.

Slash commands require the corresponding skills to be installed. The defaults use [Matt Pocock's skills](https://github.com/mattpocock/skills).

## Privacy

- No networking, telemetry, or analytics.
- Commands are stored locally.
- Accessibility access is used to locate the focused field and enter your configured text.

## Troubleshooting

- **Nothing types:** Reenable the SD Bridge accessibility service.
- **Blank key labels:** Open **Deck setup** and tap **Re-arm keys**.
- **Build fails:** Run `./build.sh`, which pins JDK 21.

## How it works

SD Bridge communicates directly with the Stream Deck over USB and types into the focused Android text field. It supports configurable submission behavior, including Enter or a send button where supported.

See the source code for implementation details.

## License

MIT. Not affiliated with or endorsed by Elgato.

