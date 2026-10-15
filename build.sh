#!/usr/bin/env bash
#
# Build DeckBridge with a Gradle-compatible JDK.
#
# WHY THIS EXISTS
#
# This box's default Java is JDK 26. The Android Gradle Plugin (8.13.0) with
# Kotlin 2.2.0 cannot parse a four-part version string like "26.0.2.1": its
# bundled IntelliJ version parser throws
#
#     java.lang.IllegalArgumentException: 26.0.2.1
#
# before the build even starts. The failure names neither Java nor the version
# problem, so it reads as a mystery build error. JDK 21 is installed alongside
# it and works, so pin to that by path rather than relying on whatever
# `java` happens to resolve to this week.
#
# Usage:
#   ./build.sh              # assemble debug APK
#   ./build.sh install      # assemble, then install and launch on the phone
#   ./build.sh <tasks...>   # any gradle task, run under the pinned JDK
#
set -euo pipefail

JDK21="/usr/lib/jvm/java-21-openjdk"

if [[ ! -x "$JDK21/bin/java" ]]; then
    echo "JDK 21 is missing. Install it with:" >&2
    echo "    sudo pacman -S jdk21-openjdk" >&2
    exit 1
fi

export JAVA_HOME="$JDK21"
export PATH="$JDK21/bin:$PATH"

cd "$(dirname "$0")"

if [[ "${1:-}" == "install" ]]; then
    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am force-stop com.hermes.deckbridge
    adb shell am start -n com.hermes.deckbridge/.MainActivity
    echo "installed and launched"
else
    ./gradlew "${@:-:app:assembleDebug}"
fi
