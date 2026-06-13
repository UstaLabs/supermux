#!/bin/sh
# scripts/test-update-flow.sh — hermetic update→swap→rollback e2e on REAL binaries.
#
# Stage-1 CI proved a binary boots + serves its embedded PWA, and Stage-2 unit
# tests cover the apply/rollback engine against fake "binaries". Neither
# exercises the FULL cycle on actual compiled supermux binaries fetched over
# HTTP from a versions.json. This does, end to end:
#
#   1. Build TWO real binaries with the canonical build-binary.sh:
#        A = 0.0.1-flow   (the "installed" binary)
#        B = 0.0.2-flow   (the "release" to update to)
#   2. Serve the workdir over HTTP (a tiny bun server) and write a versions.json
#      whose linux-x64 / linux-arm64 assets both point at B with B's real sha256.
#   3. Copy A → <workdir>/supermux, then run `supermux update` with
#      MUX_UPDATE_URL pointing at the local server. Assert:
#        - exit 0 and output mentions the update
#        - <workdir>/supermux version now starts with 0.0.2-flow
#        - <workdir>/supermux.prev exists and ITS version is 0.0.1-flow
#   4. Run `supermux rollback`. Assert (reversibility):
#        - <workdir>/supermux version is back to 0.0.1-flow
#        - <workdir>/supermux.prev version is now 0.0.2-flow
#
# Hermetic: a mktemp workdir, a high port, a trap that kills the server and
# rm -rf's the dir on every exit path. INVOCATION_ID is UNSET so the CLI never
# tries to `systemctl restart` (no systemd here / in CI) — it prints a
# restart-required hint, exits 0, and the on-disk swap is what we assert.
#
# Echoes `UPDATE FLOW OK` and exits 0 on success; clear FAIL + nonzero exit on
# any failure.
set -eu

PORT="${MUX_UPDATE_FLOW_PORT:-18799}"
VER_A="0.0.1-flow"
VER_B="0.0.2-flow"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/supermux-update-flow.XXXXXX")"
SERVER_PID=""

cleanup() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

fail() {
  echo "UPDATE FLOW FAIL: $*"
  exit 1
}

# Never let a stray systemd env make the CLI try to restart a unit mid-test.
unset INVOCATION_ID || true

echo "UPDATE-FLOW: root=$ROOT work=$WORK port=$PORT"

# ── 1. Build two real binaries (A=old, B=new) into the workdir ───────────────
# These are full ~95MB compiles (PWA + native pty-helper embed) — slow-ish but
# the only way to prove the real fetch+swap on genuine binaries.
BIN_A="$WORK/supermux-linux-x64-A"
BIN_B="$WORK/supermux-linux-x64-B"

echo "UPDATE-FLOW: building A ($VER_A)…"
"$ROOT/scripts/build-binary.sh" "$BIN_A" "$VER_A" "flowA000" >/dev/null 2>"$WORK/build-A.log" \
  || { echo "--- build-A.log (tail) ---"; tail -n 30 "$WORK/build-A.log" || true; fail "build of A failed"; }
[ -x "$BIN_A" ] || fail "A binary missing/not executable: $BIN_A"

echo "UPDATE-FLOW: building B ($VER_B)…"
"$ROOT/scripts/build-binary.sh" "$BIN_B" "$VER_B" "flowB000" >/dev/null 2>"$WORK/build-B.log" \
  || { echo "--- build-B.log (tail) ---"; tail -n 30 "$WORK/build-B.log" || true; fail "build of B failed"; }
[ -x "$BIN_B" ] || fail "B binary missing/not executable: $BIN_B"

# Sanity: the two builds really do report the versions we asked for.
A_SELF="$("$BIN_A" version 2>/dev/null || true)"
B_SELF="$("$BIN_B" version 2>/dev/null || true)"
case "$A_SELF" in "$VER_A"*) : ;; *) fail "fresh A reports '$A_SELF', expected to start with '$VER_A'" ;; esac
case "$B_SELF" in "$VER_B"*) : ;; *) fail "fresh B reports '$B_SELF', expected to start with '$VER_B'" ;; esac
echo "UPDATE-FLOW: built A='$A_SELF' B='$B_SELF'"

# ── 2. The asset B will be served as; its real sha256 goes into versions.json ─
# The apply builds the download URL straight from versions.json's asset.url, so
# we control it fully. Serve B under a stable filename and point both arch keys
# at it (we only run x64; arm64 entry is cosmetic but the schema wants both).
ASSET_NAME="supermux-linux-x64"
cp "$BIN_B" "$WORK/$ASSET_NAME"
B_SHA="$(sha256sum "$WORK/$ASSET_NAME" | cut -d' ' -f1)"
[ -n "$B_SHA" ] || fail "could not compute B sha256"
echo "UPDATE-FLOW: B sha256=$B_SHA"

# Write versions.json directly (robust; no dependence on the generator's
# hardcoded GitHub URLs). Only schemaVersion + channels.stable.{version,
# publishedAt, notesUrl, assets.linux-{x64,arm64}.{url,sha256}} are needed; the
# zod schema (src/core/update/versions.ts) requires publishedAt + notesUrl too.
BASE_URL="http://127.0.0.1:$PORT"
ASSET_URL="$BASE_URL/$ASSET_NAME"
VERSIONS_JSON="$WORK/versions.json"
ASSET_URL="$ASSET_URL" B_SHA="$B_SHA" VER_B="$VER_B" bun -e '
  const url = process.env.ASSET_URL, sha = process.env.B_SHA, version = process.env.VER_B
  const asset = { url, sha256: sha }
  process.stdout.write(JSON.stringify({
    schemaVersion: 1,
    channels: {
      stable: {
        version,
        publishedAt: new Date().toISOString(),
        notesUrl: url,
        assets: { "linux-x64": asset, "linux-arm64": asset },
      },
    },
  }, null, 2))
' > "$VERSIONS_JSON" || fail "failed to write versions.json"
echo "UPDATE-FLOW: wrote $VERSIONS_JSON"

# ── 3. Serve the workdir over HTTP (background) ──────────────────────────────
# cd into the workdir first so "." + pathname resolves the asset + versions.json.
# PORT is passed inline (env assignment in front of the command) so the
# backgrounded bun -e child reliably sees process.env.PORT.
( cd "$WORK" && exec env PORT="$PORT" bun -e '
  const port = Number(process.env.PORT)
  Bun.serve({
    port,
    fetch(req) {
      const p = "." + new URL(req.url).pathname
      return new Response(Bun.file(p))
    },
  })
  console.error("update-flow http server listening on " + port)
' ) &
SERVER_PID=$!

# Wait until the server actually answers versions.json (server boot + bind).
READY=""
i=0
while [ "$i" -lt 50 ]; do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    fail "http server exited during startup"
  fi
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/versions.json" 2>/dev/null || echo 000)"
  if [ "$CODE" = "200" ]; then READY=1; break; fi
  i=$((i + 1))
  sleep 0.2
done
[ -n "$READY" ] || fail "http server did not serve versions.json within ~10s"
echo "UPDATE-FLOW: http server ready on $BASE_URL"

# ── 4. Install A as <workdir>/supermux, then UPDATE ──────────────────────────
INSTALLED="$WORK/supermux"
cp "$BIN_A" "$INSTALLED"
chmod +x "$INSTALLED"

echo "UPDATE-FLOW: running \`supermux update\`…"
set +e
UPDATE_OUT="$(MUX_UPDATE_URL="$BASE_URL/versions.json" "$INSTALLED" update 2>&1)"
UPDATE_RC=$?
set -e
echo "$UPDATE_OUT" | sed 's/^/    update> /'
[ "$UPDATE_RC" -eq 0 ] || fail "\`update\` exited $UPDATE_RC (expected 0)"
case "$UPDATE_OUT" in
  *[Uu]pdated*) : ;;
  *) fail "\`update\` output did not mention being updated. output: $UPDATE_OUT" ;;
esac

# execPath is now B; .prev is A.
POST_UPDATE_VER="$("$INSTALLED" version 2>/dev/null || true)"
case "$POST_UPDATE_VER" in
  "$VER_B"*) : ;;
  *) fail "after update, supermux version='$POST_UPDATE_VER', expected to start with '$VER_B'" ;;
esac
echo "UPDATE-FLOW: PASS — supermux is now '$POST_UPDATE_VER'"

PREV="$INSTALLED.prev"
[ -x "$PREV" ] || fail ".prev missing/not executable after update: $PREV"
PREV_VER="$("$PREV" version 2>/dev/null || true)"
case "$PREV_VER" in
  "$VER_A"*) : ;;
  *) fail "after update, supermux.prev version='$PREV_VER', expected to start with '$VER_A'" ;;
esac
echo "UPDATE-FLOW: PASS — supermux.prev is '$PREV_VER'"

# ── 5. ROLLBACK and assert reversibility ─────────────────────────────────────
echo "UPDATE-FLOW: running \`supermux rollback\`…"
set +e
ROLLBACK_OUT="$("$INSTALLED" rollback 2>&1)"
ROLLBACK_RC=$?
set -e
echo "$ROLLBACK_OUT" | sed 's/^/    rollback> /'
[ "$ROLLBACK_RC" -eq 0 ] || fail "\`rollback\` exited $ROLLBACK_RC (expected 0)"

POST_ROLLBACK_VER="$("$INSTALLED" version 2>/dev/null || true)"
case "$POST_ROLLBACK_VER" in
  "$VER_A"*) : ;;
  *) fail "after rollback, supermux version='$POST_ROLLBACK_VER', expected to start with '$VER_A'" ;;
esac
echo "UPDATE-FLOW: PASS — rolled back, supermux is '$POST_ROLLBACK_VER'"

# Reversibility: .prev now holds B again.
PREV_VER_AFTER="$("$PREV" version 2>/dev/null || true)"
case "$PREV_VER_AFTER" in
  "$VER_B"*) : ;;
  *) fail "after rollback, supermux.prev version='$PREV_VER_AFTER', expected to start with '$VER_B' (reversibility)" ;;
esac
echo "UPDATE-FLOW: PASS — reversibility, supermux.prev is '$PREV_VER_AFTER'"

echo "UPDATE FLOW OK"
