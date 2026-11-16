# Try the #156 CLI prototype by hand, against a throwaway broker (Docker, no network) with the eval's fake world.
# Nothing here touches the live broker.
#
#   source evals/cli/try.sh        then: supermux help
#   try_cli_stop                   when done
_here="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
_repo="$(cd "$_here/../.." && pwd)"
export TRY_DIR=/tmp/ce/try
rm -rf "$TRY_DIR" /tmp/ce/try-p && mkdir -p "$TRY_DIR" /tmp/ce/try-p
bun -e "import { seed } from '$_here/seed'; seed('$TRY_DIR', '/tmp/ce/try-p')" >/dev/null
docker rm -f ce-try >/dev/null 2>&1
docker run -d --rm --name ce-try --network none --user "$(id -u):$(id -g)" -e ARM=cli \
  -v "$_repo:$_repo:ro" -v "$TRY_DIR:$TRY_DIR" -v /tmp/ce/try-p:/tmp/ce/try-p -v "$(command -v bun):/usr/local/bin/bun:ro" \
  -w "$_here" ubuntu:24.04 bun broker.ts "$TRY_DIR" /tmp/ce/try-p >/dev/null
for _i in $(seq 1 50); do [ -f /tmp/ce/try-p/broker.ready ] && break; sleep 0.1; done
bun build --target=bun "$_here/cli.ts" --outfile "$TRY_DIR/supermux.js" >/dev/null
export MUX_SESSION_ID=e0a1b2c3-0000-4000-8000-000000000156 MUX_SOCKETS_DIR="$TRY_DIR/s"
supermux() { bun "$TRY_DIR/supermux.js" "$@"; }
try_cli_state() { python3 -m json.tool /tmp/ce/try-p/state.json | less; }
try_cli_stop() { docker rm -f ce-try >/dev/null 2>&1; unset -f supermux try_cli_state try_cli_stop; unset MUX_SESSION_ID MUX_SOCKETS_DIR; echo "stopped"; }
cd "$TRY_DIR/w"
echo "supermux CLI prototype ready (fake world, throwaway broker). Try: supermux help · supermux session ls · try_cli_stop when done"
