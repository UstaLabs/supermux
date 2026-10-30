#!/usr/bin/env bash
# The Linux engine's checks (sample/linux/LinuxChecks.kt), in the sample app on `desktop`: in Ubuntu 24.04 with no WPE
# of its own (webview-wpe-run:24.04), on the WPE runtime runtime/linux/runtime.sh built there. Runs ON THIS BOX; the
# sample's jars are built on the Mac (webview-mac-gradle), for Linux x64.
#   device-checks/linux-engine-checks.sh [check name]   every check, or the one named
#   MODE=lab device-checks/linux-engine-checks.sh       the sample itself on WPE engines (Task 28)
#   SKIP_STAGE=1 reuses the jars staged last            OUT=<dir> for the logs (default: build/linux-checks)
# Exit status: 0 when every check passed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(dirname "$HERE")"
APPS="$(dirname "$MODULE")"
MAIN=/home/ahmet/.mux/worktrees/supermux-3962b5bf/c58b82b4-abaf-40d9-a195-6376a386ddd1
NAME=wpe-runtime-2.54.2-linux-x64
WORK=work/webview-linux-checks
MODE="${MODE:-checks}"
ONLY="${1:-}"
OUT="${OUT:-$MODULE/build/linux-checks}"
SSHO=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=15)
die() { echo "linux-checks: $*" >&2; exit 1; }
d() { ssh "${SSHO[@]}" desktop "$1"; }

timeout 20 ssh "${SSHO[@]}" desktop true 2>/dev/null || die "desktop doesn't answer: the checks run there"
d "test -d work/wpe-runtime/out/$NAME && docker image inspect webview-wpe-run:24.04 >/dev/null" \
  || die "no runtime or run image on desktop: runtime/linux/runtime.sh image, then build"
mkdir -p "$OUT"
if [ -z "${SKIP_STAGE:-}" ]; then
  (cd "$APPS" && webview-mac-gradle -Pwebview.sample.linux :webview-sample:stageLinux)
  root="$(git -C "$APPS" rev-parse --show-toplevel)"
  macdir=work/webview-compose
  [ "$root" = "$MAIN" ] || macdir="work/webview-compose-$(printf %s "$root" | sha1sum | cut -c1-8)"
  rm -rf "$MODULE/build/linux/lib"
  mkdir -p "$MODULE/build/linux"
  scp -q -r "mac:$macdir/apps/webview-sample/build/linux/lib" "$MODULE/build/linux/"
  ls "$MODULE/build/linux/lib" | grep -q '^skiko-awt-runtime-linux-x64' || die "the staged classpath has no Linux skiko"
  d "mkdir -p $WORK/lib"
  rsync -a --delete "$MODULE/build/linux/lib/" "desktop:$WORK/lib/"
fi
rsync -a "$HERE/linux-run.sh" "desktop:$WORK/"
code=0
d "cd $WORK && rm -rf out && mkdir -p out home && docker run --rm --init --network=none --shm-size=1g \
  --memory=4g --memory-swap=4g --cpus=4 --user \$(id -u):\$(id -g) -e HOME=/work/home -v \$PWD:/work \
  -v \$HOME/work/wpe-runtime/out/$NAME:/opt/wpe-runtime:ro webview-wpe-run:24.04 bash /work/linux-run.sh $MODE $ONLY" \
  >"$OUT/$MODE.log" 2>&1 || code=$?
rsync -a "desktop:$WORK/out/" "$OUT/"
grep -E '^CHECK |^CHECKS |^perf-lab |^SHOT |^LAB |^webview-sample: ' "$OUT/$MODE.log" || true
echo "linux-checks: $MODE exited $code; logs in $OUT"
exit "$code"
