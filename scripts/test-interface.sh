#!/usr/bin/env bash
set -euo pipefail
collect() {
  mkdir -p build/screenshots
  adb exec-out run-as com.weskestis.astrastudio tar -C files -cf - screenshots > build/screenshots.tar || true
  tar -xf build/screenshots.tar -C build || true
  adb logcat -d > build/ui-logcat.txt || true
}
trap collect EXIT
adb shell wm size 1080x2520
adb shell wm density 420
bash gradlew --no-daemon :app:connectedDebugAndroidTest
