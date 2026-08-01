#!/bin/sh
# Run the Maestro flows against whatever device is attached, inside the broker
# fixture. scripts/test-broker.sh execs this, so MUX_TEST_BASE_URL /
# MUX_TEST_PAIR_TOKEN / MUX_TEST_SESSION_ID are already in the environment.
#
# Not inlined into test-android.sh as `sh -c '…'`: the trap below needs quotes
# that would terminate the surrounding single-quoted string, and the broken
# quoting still passes `sh -n`.
#
#   env: MAESTRO_BIN, MUX_TEST_FLOWS, MUX_TEST_HOST_ADDR (optional)
set -eu

: "${MAESTRO_BIN:?maestro-run.sh: MAESTRO_BIN not set}"
: "${MUX_TEST_FLOWS:?maestro-run.sh: MUX_TEST_FLOWS not set}"
: "${MUX_TEST_BASE_URL:?maestro-run.sh: must run inside scripts/test-broker.sh}"

# The emulator cannot reach the host's 127.0.0.1; 10.0.2.2 is mapped to it. A
# physical device needs the host's LAN IP — set MUX_TEST_HOST_ADDR.
HOST_ADDR="${MUX_TEST_HOST_ADDR:-10.0.2.2}"
PORT="${MUX_TEST_BASE_URL##*:}"
BASE="http://${HOST_ADDR}:${PORT}"
echo "android journey → $BASE" >&2

# Flows are RENDERED with real values rather than passed via `maestro -e`:
# maestro preferred the flow's own `env:` defaults over -e, so a run typed
# ".../pair?t=unset", reached the right screen, and failed in a way that looked
# like a UI bug rather than a wiring bug. Substitution up front cannot do that.
RENDERED="$(mktemp -d "${TMPDIR:-/tmp}/supermux-maestro.XXXXXX")"
trap 'rm -rf "$RENDERED"' EXIT INT TERM

PROMPT="maestro-$(date +%s)"
find "$MUX_TEST_FLOWS" \( -name '*.yaml' -o -name '*.yml' \) -print | while IFS= read -r f; do
  sed \
    -e "s|\${BASE_URL}|$BASE|g" \
    -e "s|\${PAIR_TOKEN}|$MUX_TEST_PAIR_TOKEN|g" \
    -e "s|\${SESSION_ID}|$MUX_TEST_SESSION_ID|g" \
    -e "s|\${PROMPT}|$PROMPT|g" \
    "$f" > "$RENDERED/$(basename "$f")"
done

# On failure, capture what was actually on screen. maestro writes its own
# hierarchy, but not when the run dies because the device went offline — and a
# device lane you cannot see is a device lane you cannot fix.
if "$MAESTRO_BIN" test "$RENDERED"; then
  exit 0
fi
status=$?
OUT="${MUX_TEST_STATE_DIR:-${TMPDIR:-/tmp}}/maestro-failure"
mkdir -p "$OUT"
adb shell uiautomator dump /sdcard/mux-ui.xml >/dev/null 2>&1 &&
  adb pull /sdcard/mux-ui.xml "$OUT/hierarchy.xml" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus > "$OUT/focus.txt" || true
echo "captured device state → $OUT" >&2
exit "$status"
