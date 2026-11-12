#!/usr/bin/env bash
set -euo pipefail

# Use a dedicated unlocked Android device in portrait. Keep its display mode fixed.
apk=${1:?Usage: measure-scroll.sh APK OUTPUT_DIR [RUNS]}
out=${2:?Specify an output directory}
runs=${3:-3}
pkg=de.meshcore.pocketmonitor.scrollbenchmark
mkdir -p "$out"
adb install -r "$apk"
adb shell cmd package compile -m speed -f "$pkg"
adb shell dumpsys display > "$out/display.txt"
size=$(adb shell wm size | tr -d '\r' | tail -1 | awk '{print $NF}')
width=${size%x*}
height=${size#*x}
x=$((width / 2))
upper=$((height * 28 / 100))
lower=$((height * 80 / 100))

for ((run=1; run<=runs; run++)); do
    for scenario in live:100 live:50000 chat:100 chat:50000; do
        mode=${scenario%:*}
        rows=${scenario#*:}
        if [[ $mode == chat ]]; then
            chat=true
            start=$upper
            end=$lower
        else
            chat=false
            start=$lower
            end=$upper
        fi
        gesture="input swipe $x $start $x $end 350"
        back="input swipe $x $end $x $start 350"
        adb shell am force-stop "$pkg"
        adb shell am start -W -n "$pkg/de.meshcore.pocketmonitor.ScrollBenchmarkActivity" \
            --ez chat "$chat" --ei rows "$rows"
        sleep 2
        adb shell "$gesture; $back"
        adb shell dumpsys gfxinfo "$pkg" reset >/dev/null
        adb shell "$gesture; $gesture; $back; $back; $gesture; $gesture; $back; $back"
        adb shell dumpsys gfxinfo "$pkg" framestats > "$out/$mode-$rows-$run.txt"
        awk '/^50th gpu percentile:/ { exit }
             /^(Total frames rendered|Janky frames|50th percentile|95th percentile):/ { print }' \
            "$out/$mode-$rows-$run.txt"
    done
done
