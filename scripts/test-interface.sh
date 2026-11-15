#!/usr/bin/env bash
set -euo pipefail
collect() {
  mkdir -p build/screenshots
  adb pull /sdcard/Pictures/AstraStudio build/screenshots || true
  adb logcat -d > build/ui-logcat.txt || true
}
trap collect EXIT
adb shell wm size 1080x2520
adb shell wm density 420
bash gradlew --no-daemon :app:connectedDebugAndroidTest
adb shell test -s /sdcard/Pictures/AstraStudio/04-arrangement-landscape.png
