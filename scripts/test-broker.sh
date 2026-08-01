#!/bin/sh
# Run a command against a real broker with throwaway state and a deterministic
# fake Claude shim. The live ~/.mux state and :9898 broker are never touched.
set -eu

if [ "$#" -eq 0 ]; then
  echo "usage: scripts/test-broker.sh <command> [args...]" >&2
  exit 2
fi

REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
FIXTURE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/supermux-test-broker.XXXXXX")"
FIXTURE_STATE="$FIXTURE_DIR/state"
FIXTURE_WORKDIR="$FIXTURE_DIR/workdir"
FIXTURE_LOG="$FIXTURE_DIR/broker.log"
AGENT_LOG="$FIXTURE_DIR/agent.log"
BROKER_PID=""
AGENT_PID=""

cleanup() {
  if [ -n "$AGENT_PID" ]; then
    kill "$AGENT_PID" 2>/dev/null || true
    wait "$AGENT_PID" 2>/dev/null || true
  fi
  if [ -n "$BROKER_PID" ]; then
    kill "$BROKER_PID" 2>/dev/null || true
    wait "$BROKER_PID" 2>/dev/null || true
  fi
  if [ "${MUX_TEST_KEEP_FIXTURE:-0}" = "1" ]; then
    echo "test broker fixture kept at $FIXTURE_DIR" >&2
  else
    rm -rf "$FIXTURE_DIR"
  fi
}
trap cleanup EXIT INT TERM

fail() {
  echo "test broker failed: $*" >&2
  if [ -f "$FIXTURE_LOG" ]; then
    echo "--- broker log ---" >&2
    tail -n 80 "$FIXTURE_LOG" >&2 || true
  fi
  if [ -f "$AGENT_LOG" ]; then
    echo "--- agent log ---" >&2
    tail -n 40 "$AGENT_LOG" >&2 || true
  fi
  exit 1
}

mkdir -p "$FIXTURE_STATE" "$FIXTURE_WORKDIR" "$FIXTURE_DIR/stubbin"
printf '#!/bin/sh\nexit 0\n' > "$FIXTURE_DIR/stubbin/claude"
printf '#!/bin/sh\ncase "$1" in -V) echo "tmux 3.4";; esac\nexit 0\n' > "$FIXTURE_DIR/stubbin/tmux"
chmod +x "$FIXTURE_DIR/stubbin/claude" "$FIXTURE_DIR/stubbin/tmux"

cd "$REPO_ROOT"
if [ "${MUX_TEST_SKIP_WEB_BUILD:-0}" != "1" ]; then
  (cd src/web-app && bun run build)
fi

PORT="$(bun -e 'const listener=Bun.listen({hostname:"127.0.0.1",port:0,socket:{data(){}}}); console.log(listener.port); listener.stop(true)')"
case "$PORT" in
  ''|*[!0-9]*) fail "could not allocate an isolated port" ;;
  9898) fail "refusing to use the live broker port" ;;
esac
BASE_URL="http://127.0.0.1:$PORT"

SEED_JSON="$(
  MUX_HOME="$FIXTURE_DIR" \
  MUX_STATE_DIR="$FIXTURE_STATE" \
  MUX_TEST_WORKDIR="$FIXTURE_WORKDIR" \
    bun scripts/test-broker-seed.ts
)"
PAIR_TOKEN="$(printf '%s' "$SEED_JSON" | bun -e 'console.log(JSON.parse(await Bun.stdin.text()).token)')"
[ -n "$PAIR_TOKEN" ] || fail "seed did not return a pairing token"

env \
  -u MUX_TELEGRAM_BOT_TOKEN \
  -u MUX_WHATSAPP_GOWA_URL \
  -u MUX_WHATSAPP_GOWA_BASIC_AUTH \
  -u MUX_WHATSAPP_GOWA_DEVICE_ID \
  -u MUX_WHATSAPP_WEBHOOK_PORT \
  -u MUX_WHATSAPP_WEBHOOK_SECRET \
  -u MUX_RELAY_DOMAIN \
  -u MUX_RELAY_BASE \
  -u ANTHROPIC_API_KEY \
  -u CLAUDE_CODE_OAUTH_TOKEN \
  -u OPENAI_API_KEY \
  -u CURSOR_API_KEY \
  PATH="$FIXTURE_DIR/stubbin:$PATH" \
  MUX_TEST_BROKER=1 \
  MUX_HOME="$FIXTURE_DIR" \
  MUX_STATE_DIR="$FIXTURE_STATE" \
  MUX_SOCKETS_DIR="$FIXTURE_STATE/sockets" \
  MUX_WEB_PORT="$PORT" \
  MUX_WEB_PUBLIC_URL="$BASE_URL" \
  MUX_UPDATE_CHECK=0 \
  MUX_CURATOR_ENABLED=0 \
    bun src/main.ts >"$FIXTURE_LOG" 2>&1 &
BROKER_PID=$!

READY=""
i=0
while [ "$i" -lt 120 ]; do
  if ! kill -0 "$BROKER_PID" 2>/dev/null; then
    fail "broker exited during startup"
  fi
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/me" 2>/dev/null || true)"
  if [ "$CODE" = "401" ]; then
    READY=1
    break
  fi
  i=$((i + 1))
  sleep 0.25
done
[ "$READY" = "1" ] || fail "broker did not become ready"

MUX_SOCKETS_DIR="$FIXTURE_STATE/sockets" \
MUX_TEST_SESSION_ID="00000000-0000-4000-8000-000000000001" \
MUX_TEST_SESSION_NAME="test-journey" \
MUX_TEST_WORKDIR="$FIXTURE_WORKDIR" \
  bun scripts/test-agent.ts >"$AGENT_LOG" 2>&1 &
AGENT_PID=$!

AGENT_READY=""
i=0
while [ "$i" -lt 80 ]; do
  if ! kill -0 "$AGENT_PID" 2>/dev/null; then
    fail "fake agent exited during startup"
  fi
  if grep -q '"ready":true' "$AGENT_LOG" 2>/dev/null; then
    AGENT_READY=1
    break
  fi
  i=$((i + 1))
  sleep 0.1
done
[ "$AGENT_READY" = "1" ] || fail "fake agent did not connect"

BROWSER_BIN=""
for candidate in google-chrome google-chrome-stable chromium chromium-browser; do
  if command -v "$candidate" >/dev/null 2>&1; then
    BROWSER_BIN="$(command -v "$candidate")"
    break
  fi
done

printf '%s\n' "{\"baseUrl\":\"$BASE_URL\",\"stateDir\":\"$FIXTURE_STATE\",\"sessionId\":\"00000000-0000-4000-8000-000000000001\",\"sessionName\":\"test-journey\",\"deviceName\":\"playwright-fixture\"}"

env \
MUX_RUN_UI_SMOKE=1 \
MUX_TEST_BASE_URL="$BASE_URL" \
MUX_TEST_PAIR_TOKEN="$PAIR_TOKEN" \
MUX_TEST_DEVICE_NAME="playwright-fixture" \
MUX_TEST_SESSION_ID="00000000-0000-4000-8000-000000000001" \
MUX_TEST_SESSION_NAME="test-journey" \
MUX_TEST_BROWSER_BIN="$BROWSER_BIN" \
MUX_HOME="$FIXTURE_DIR" \
MUX_STATE_DIR="$FIXTURE_STATE" \
MUX_SOCKETS_DIR="$FIXTURE_STATE/sockets" \
MUX_WEB_PORT="$PORT" \
  "$@"
