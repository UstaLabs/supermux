#!/usr/bin/env bash
# Runs ON the Mac, over plain SSH: the fallback screenshot route (shots=external). Captures every window the app asks
# for (<name>.req holds its window number) until the app writes LAYOUT-TEST DONE, for 15 minutes at most.
#   shoot-loop.sh <out dir>
set -euo pipefail
OUT="${1:?usage: shoot-loop.sh <out dir>}"
END=$((SECONDS + 900))
while ((SECONDS < END)) && ! grep -q 'LAYOUT-TEST DONE' "$OUT/app.log" 2>/dev/null; do
  for request in "$OUT"/shots/*.req; do
    [[ -e "$request" ]] || continue
    png="${request%.req}.png"
    /usr/sbin/screencapture -x -o -l "$(cat "$request")" "$png.part.png" && mv "$png.part.png" "$png"
    rm -f "$request"
  done
  sleep 0.05
done
