#!/usr/bin/env python3
"""
Strip the Termux delivery path from DeckBridge.

WHY: Jon does not use the deck to drive Termux. Collie is the priority and
Discord is the secondary; both are ordinary text fields reached through the
accessibility path. Carrying the Termux branch means carrying the RUN_COMMAND
intent plumbing, a TERMUX target that has to be explained in the UI, and a
whole class's worth of code that can only regress.

Done as a script rather than hand-edits because the references are spread over
seven files and a partial removal leaves the project not compiling.

Run with --dry-run first to see the reference count per file.
"""
import re
import sys
import pathlib

SRC = pathlib.Path("app/src/main/java/com/hermes/deckbridge")
MANIFEST = pathlib.Path("app/src/main/AndroidManifest.xml")


def strip_kt(path: pathlib.Path) -> tuple[int, str]:
    """Remove Termux code from a Kotlin file. Returns (removals, new_text)."""
    text = path.read_text()
    before = text
    n = 0

    # 1. Drop whole TERMUX/terminal delivery functions by brace matching.
    for fname in ("deliverToTermux", "deliverRawTermux", "deliverTextToTerminal",
                  "testTermux", "deliverToTerminal"):
        while True:
            m = re.search(rf"\n(\s*)(?:private\s+)?(?:suspend\s+)?fun\s+{fname}\s*\(", text)
            if not m:
                break
            start = m.start()
            # find body open brace
            i = text.index("{", m.end())
            depth = 0
            j = i
            while j < len(text):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            # include a preceding KDoc block if present
            head = text.rfind("/**", 0, start)
            if head != -1 and text[head:start].strip().endswith("*/"):
                start = head
            text = text[:start] + text[j + 1:]
            n += 1

    # 2. Remove the TERMUX enum member and its comment.
    text = re.sub(
        r"\n\s*/\*\* Send to Termux[^*]*\*/\s*\n\s*TERMUX,\s*\n", "\n", text
    )

    # 3. Remove Termux constants.
    for const in ("TERMUX_SHELL", "TERMUX_SERVICE", "TERMUX_ACTION", "TERMINAL_PACKAGES"):
        text = re.sub(rf"\n\s*(?:private\s+)?(?:const\s+)?val\s+{const}\s*[:=][^\n]*\n", "\n", text)

    if text != before:
        path.write_text(text)
    return n, text


def main() -> int:
    dry = "--dry-run" in sys.argv
    total = 0
    for p in sorted(SRC.glob("*.kt")):
        t = p.read_text()
        hits = len(re.findall(r"[Tt]ermux|RUN_COMMAND|TERMINAL", t))
        if hits:
            print(f"{p.name}: {hits} references")
            total += hits
    print(f"\ntotal references: {total}")

    # Manifest: the RUN_COMMAND permission and the <queries> block.
    m = MANIFEST.read_text()
    print(f"\nAndroidManifest.xml: {len(re.findall(r'termux|RUN_COMMAND', m))} references")

    if dry:
        print("\n--dry-run: nothing written")
        return 0

    grand = 0
    for p in sorted(SRC.glob("*.kt")):
        n, _ = strip_kt(p)
        if n:
            print(f"stripped {n} function(s) from {p.name}")
        grand += n

    # Manifest edits.
    m = re.sub(
        r"\n\s*<!--\s*\n\s*Required to run shell commands inside Termux.*?-->\s*\n"
        r"\s*<uses-permission android:name=\"com\.termux\.permission\.RUN_COMMAND\" />\s*\n",
        "\n", m, flags=re.S,
    )
    m = re.sub(
        r"\n\s*<queries>\s*\n\s*<package android:name=\"com\.termux\" />\s*\n\s*</queries>\s*\n",
        "\n", m, flags=re.S,
    )
    MANIFEST.write_text(m)
    print(f"\nstripped {grand} functions; manifest cleaned")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
