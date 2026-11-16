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
adb shell test -s /sdcard/Pictures/AstraStudio/05-astra-preview.png
adb shell test -s /sdcard/Pictures/AstraStudio/06-astra-settings.png
adb shell test -s /sdcard/Pictures/AstraStudio/07-drum-rack.png
adb shell test -s /sdcard/Pictures/AstraStudio/08-pad-editor.png
adb shell test -s /sdcard/Pictures/AstraStudio/09-sample-browser.png
adb shell test -s /sdcard/Pictures/AstraStudio/10-resample.png
adb shell test -s /sdcard/Pictures/AstraStudio/11-sampler.png

adb shell test -s /sdcard/Pictures/AstraStudio/12-tempo-map.png
adb shell test -s /sdcard/Pictures/AstraStudio/13-stretch.png
adb shell test -s /sdcard/Pictures/AstraStudio/14-float-export.png

adb shell test -s /sdcard/Pictures/AstraStudio/15-recording-waveform.png
adb shell test -s /sdcard/Pictures/AstraStudio/16-bass-analysis.png
adb shell test -s /sdcard/Pictures/AstraStudio/17-recording-landscape.png
adb shell test -s /sdcard/Pictures/AstraStudio/18-recorded-take.png

adb shell test -s /sdcard/Pictures/AstraStudio/19-warp-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/20-warp-landscape.png

adb shell test -s /sdcard/Pictures/AstraStudio/21-finished-track-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/22-finished-track-landscape.png

adb shell test -s /sdcard/Pictures/AstraStudio/23-master192-export-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/24-master192-export-landscape.png
adb shell test -s /sdcard/Pictures/AstraStudio/25-master192-finished.png

adb shell test -s /sdcard/Pictures/AstraStudio/26-fifty-tracks-session-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/27-fifty-tracks-arrangement-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/28-fifty-tracks-landscape.png

adb shell test -s /sdcard/Pictures/AstraStudio/29-freeze-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/30-freeze-landscape.png
adb shell test -s /sdcard/Pictures/AstraStudio/31-frozen-track.png
adb shell test -s /sdcard/Pictures/AstraStudio/32-recovery-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/33-performance-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/34-performance-landscape.png

adb shell test -s /sdcard/Pictures/AstraStudio/35-pack-browser-phone.png
adb shell test -s /sdcard/Pictures/AstraStudio/36-pack-detail-landscape.png
adb shell test -s /sdcard/Pictures/AstraStudio/37-imported-wavetable.png
