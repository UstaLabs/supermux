#!/usr/bin/env bash
# Runs ON THE MAC, over plain SSH: takes the screenshots the sample asks for (<name>.req holds its window's number) with
# `screencapture -l`, which the SSH session may run and java may not (Screen Recording permission). Only the sample's own
# window is ever captured. Stops when <dir>/DONE appears, or after 10 minutes.
#   mac-shoot-loop.sh <dir>
set -euo pipefail
DIR="${1:?usage: mac-shoot-loop.sh <dir>}"
END=$((SECONDS + 600))
mkdir -p "$DIR"
while ((SECONDS < END)) && [[ ! -e "$DIR/DONE" ]]; do
  for request in "$DIR"/*.req; do
    [[ -e "$request" ]] || continue
    png="${request%.req}.png"
    /usr/sbin/screencapture -x -o -l "$(cat "$request")" "$png.part.png" && mv "$png.part.png" "$png"
    rm -f "$request"
  done
  sleep 0.05
done
