#!/bin/sh
# scripts/smoke-binary.sh — end-to-end smoke test for a compiled supermux binary.
#   usage: scripts/smoke-binary.sh <binary> [port] [expected-version]
#
# Same script locally and in CI. Proves the binary actually WORKS, not just that
# it compiled: it runs the real broker against throwaway state and exercises the
# embedded PWA + token auth over HTTP.
#
# Checks (each numbered check below MUST pass; any failure ⇒ nonzero exit):
#   1. `version` exits 0 and prints something. If expected-version is given the
#      output must START WITH it; and if it starts with "dev" we FAIL hard — that
#      means a release got built without the --define version/commit (a footgun
#      we never want to ship).
#   2. The broker boots against ISOLATED state and serves /me → 401 (the unauthed
#      paired-status probe) within ~30s.
#   3. Embedded PWA + auth: pair a device, turn the pairing token into the real
#      HttpOnly cmux_token cookie via GET /pair?t=… (the exact PWA flow), then
#      fetch the PWA shell authenticated (→ 200, body has <html/<!doctype) AND
#      one hashed /assets/*.js parsed out of that shell (→ 200). This is the part
#      that only passes if the whole PWA is genuinely embedded in the binary.
#
# Isolation: a mktemp MUX_HOME/MUX_STATE_DIR and a high port (default 18791) so a
# live broker on :9898 with real state in ~/.mux is never touched. A trap kills
# the broker and rm -rf's the temp dir on every exit path.
set -eu

BIN="${1:?usage: smoke-binary.sh <binary> [port] [expected-version]}"
PORT="${2:-18791}"
EXPECTED_VERSION="${3:-}"

# Resolve to an absolute path so cd/relative invocation can't lose the binary.
case "$BIN" in
  /*) : ;;
  *)  BIN="$(pwd)/$BIN" ;;
esac
if [ ! -x "$BIN" ]; then
  echo "SMOKE FAIL: binary not found or not executable: $BIN"
  exit 1
fi

BASE="http://127.0.0.1:$PORT"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/supermux-smoke.XXXXXX")"
BOOT_LOG="$TMP/boot.log"
COOKIE_JAR="$TMP/cookies.txt"
BROKER_PID=""

cleanup() {
  if [ -n "$BROKER_PID" ]; then
    kill "$BROKER_PID" 2>/dev/null || true
    wait "$BROKER_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

fail() {
  echo "SMOKE FAIL: $*"
  if [ -f "$BOOT_LOG" ]; then
    echo "--- boot.log (tail) ---"
    tail -n 40 "$BOOT_LOG" || true
    echo "--- end boot.log ---"
  fi
  exit 1
}

echo "SMOKE: binary=$BIN port=$PORT state=$TMP${EXPECTED_VERSION:+ expected=$EXPECTED_VERSION}"

# ── Check 1: version ────────────────────────────────────────────────────────
VERSION_OUT="$("$BIN" version)" || fail "\`version\` exited nonzero"
if [ -z "$VERSION_OUT" ]; then
  fail "\`version\` printed nothing"
fi
echo "SMOKE: version -> $VERSION_OUT"
if [ -n "$EXPECTED_VERSION" ]; then
  case "$VERSION_OUT" in
    "$EXPECTED_VERSION"*) : ;;
    *) fail "version output '$VERSION_OUT' does not start with expected '$EXPECTED_VERSION'" ;;
  esac
  # A release that starts with "dev" was compiled without --define — refuse it.
  case "$VERSION_OUT" in
    dev*) fail "version starts with 'dev' — binary built without --define version/commit" ;;
  esac
fi
echo "PASS 1/3: version"

# ── Check 2: broker boots on isolated state, /me → 401 ──────────────────────
# Both MUX_WEB_PORT and MUX_WEB_PUBLIC_URL are required for the web channel; the
# isolated MUX_HOME/MUX_STATE_DIR guarantee we never read/write real state.
MUX_HOME="$TMP" \
MUX_STATE_DIR="$TMP/state" \
MUX_WEB_PORT="$PORT" \
MUX_WEB_PUBLIC_URL="$BASE" \
  "$BIN" >"$BOOT_LOG" 2>&1 &
BROKER_PID=$!

ME_CODE=""
i=0
while [ "$i" -lt 60 ]; do
  if ! kill -0 "$BROKER_PID" 2>/dev/null; then
    fail "broker process exited during boot"
  fi
  ME_CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/me" 2>/dev/null || echo 000)"
  if [ "$ME_CODE" = "401" ]; then
    break
  fi
  i=$((i + 1))
  sleep 0.5
done
if [ "$ME_CODE" != "401" ]; then
  fail "broker /me did not return 401 within ~30s (last code: ${ME_CODE:-none})"
fi
echo "PASS 2/3: broker boots, /me -> 401"

# ── Check 3: embedded PWA serving with auth ─────────────────────────────────
# Pair a device. pair.ts reads MUX_WEB_PUBLIC_URL from the env (it only falls
# back to state/.env when the var is unset), and writes the device record via
# DeviceStore into the isolated MUX_STATE_DIR. DeviceStore is read per-request,
# so pairing AFTER boot is fine.
PAIR_OUT="$(MUX_HOME="$TMP" MUX_STATE_DIR="$TMP/state" MUX_WEB_PUBLIC_URL="$BASE" "$BIN" pair smoke-device 2>&1)" \
  || fail "\`pair\` exited nonzero. output: $PAIR_OUT"

# pair prints a line like:  http://127.0.0.1:PORT/pair?t=<token>
PAIR_URL="$(printf '%s\n' "$PAIR_OUT" | grep -oE 'https?://[^ ]*/pair\?t=[A-Za-z0-9_-]+' | head -1)"
[ -n "$PAIR_URL" ] || fail "could not parse pairing URL from pair output: $PAIR_OUT"
TOKEN="${PAIR_URL##*t=}"
[ -n "$TOKEN" ] || fail "could not parse token from pairing URL: $PAIR_URL"
echo "SMOKE: paired (token ${TOKEN%${TOKEN#????????}}…)"

# Turn the one-time pairing token into the HttpOnly cmux_token cookie exactly as
# the PWA does: GET /pair?t=… returns 302 + Set-Cookie. Capture into a jar (do
# NOT follow the redirect — we just want the cookie).
PAIR_CODE="$(curl -s -o /dev/null -w '%{http_code}' -c "$COOKIE_JAR" "$BASE/pair?t=$TOKEN" 2>/dev/null || echo 000)"
case "$PAIR_CODE" in
  30[0-9]) : ;;
  *) fail "GET /pair?t=… expected a 30x redirect, got $PAIR_CODE" ;;
esac
grep -q "cmux_token" "$COOKIE_JAR" 2>/dev/null || fail "no cmux_token cookie issued by /pair"

# Sanity: the cookie really authenticates (/me → 200 now).
ME_AUTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$BASE/me" 2>/dev/null || echo 000)"
[ "$ME_AUTH_CODE" = "200" ] || fail "authenticated /me expected 200, got $ME_AUTH_CODE"

# Fetch the embedded PWA shell, authenticated.
SHELL_FILE="$TMP/index.html"
SHELL_CODE="$(curl -s -o "$SHELL_FILE" -w '%{http_code}' -b "$COOKIE_JAR" "$BASE/" 2>/dev/null || echo 000)"
[ "$SHELL_CODE" = "200" ] || fail "GET / (PWA shell) expected 200, got $SHELL_CODE"
if ! grep -qiE '<html|<!doctype' "$SHELL_FILE"; then
  fail "PWA shell body did not contain <html or <!doctype (embedded serving broken?)"
fi

# Parse a hashed asset path out of the served shell and fetch it, authenticated.
ASSET_PATH="$(grep -oE 'assets/[^"'"'"']*\.js' "$SHELL_FILE" | head -1)"
[ -n "$ASSET_PATH" ] || fail "no assets/*.js reference found in served index.html"
ASSET_CODE="$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$BASE/$ASSET_PATH" 2>/dev/null || echo 000)"
[ "$ASSET_CODE" = "200" ] || fail "GET /$ASSET_PATH (hashed asset) expected 200, got $ASSET_CODE"
echo "SMOKE: served shell + asset /$ASSET_PATH"
echo "PASS 3/3: embedded PWA + auth"

echo "SMOKE PASS: $BIN (all checks passed)"
