#!/bin/bash
# Runs the device tests on one named device or emulator, from a cleared install, because the
# first-run path is what a phone with an old build on it never exercises.
#
#   ANDROID_SERIAL=emulator-5600 tools/device-check.sh
#
# The serial is required. Without it Gradle would install on every attached device.
set -euo pipefail
: "${ANDROID_SERIAL:?Name the device: ANDROID_SERIAL=<serial> tools/device-check.sh}"
cd "$(dirname "$0")/.."
SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
ADB="$SDK/platform-tools/adb"
"$ADB" -s "$ANDROID_SERIAL" uninstall au.com.uulabs.uudownloader >/dev/null 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" uninstall au.com.uulabs.uudownloader.test >/dev/null 2>&1 || true
./gradlew --no-daemon :app:connectedDebugAndroidTest "$@"
