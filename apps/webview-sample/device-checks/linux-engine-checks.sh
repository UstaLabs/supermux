#!/usr/bin/env bash
# The Linux engine's checks (sample/linux/LinuxChecks.kt), in the sample app on `desktop`: in Ubuntu 24.04 with no WPE
# of its own (webview-wpe-run:24.04), on the WPE runtime runtime/linux/runtime.sh built there. Runs ON THIS BOX; the
# sample's jars are built on the Mac (webview-mac-gradle), for Linux x64.
#   device-checks/linux-engine-checks.sh [check name]   every check, or the one named
#   MODE=lab device-checks/linux-engine-checks.sh       the sample itself on WPE engines (Task 28)
#   MODE=draw device-checks/linux-engine-checks.sh      the draw lab (linux-run.sh: DRAW=, RENDER_API=), a DRAW line
#   SKIP_STAGE=1 reuses the jars staged last            OUT=<dir> for the logs (default: build/linux-checks)
#   SANDBOX=on runs pages in WebKit's bubblewrap sandbox: the container then lets bubblewrap make namespaces (seccomp,
#   AppArmor and /proc unmasked for it; WebKit applies its own seccomp filter in the sandbox). Default off, as Docker
#   gives bubblewrap no user namespaces otherwise.
#   RUNTIME=<dir on desktop> another runtime (default work/wpe-runtime/out/<name>); WORK=<dir on desktop> another work
#   folder (default work/webview-linux-checks); RUN_IMAGE=<image> another run image (default webview-wpe-run:24.04)
#   KEEP_HOME=1 keeps the home folder of the run before (profiles, cookies): a restart, for a-login-survives-a-restart
# Exit status: 0 when every check passed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(dirname "$HERE")"
APPS="$(dirname "$MODULE")"
MAIN=/home/ahmet/.mux/worktrees/supermux-3962b5bf/c58b82b4-abaf-40d9-a195-6376a386ddd1
NAME=wpe-runtime-2.54.2-linux-x64
WORK="${WORK:-work/webview-linux-checks}"
RUNTIME="${RUNTIME:-work/wpe-runtime/out/$NAME}"
RUN_IMAGE="${RUN_IMAGE:-webview-wpe-run:24.04}"
SANDBOX="${SANDBOX:-off}"
MODE="${MODE:-checks}"
ONLY="${1:-}"
OUT="${OUT:-$MODULE/build/linux-checks}"
SSHO=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=15)
die() { echo "linux-checks: $*" >&2; exit 1; }
d() { ssh "${SSHO[@]}" desktop "$1"; }

timeout 20 ssh "${SSHO[@]}" desktop true 2>/dev/null || die "desktop doesn't answer: the checks run there"
d "test -d $RUNTIME && docker image inspect $RUN_IMAGE >/dev/null" \
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
sandboxed=""
[ "$SANDBOX" = on ] && sandboxed="--security-opt seccomp=unconfined --security-opt apparmor=unconfined --security-opt systempaths=unconfined"
home=home
[ -n "${KEEP_HOME:-}" ] && home=""
d "cd $WORK && rm -rf out $home && mkdir -p out home && docker run --rm --init --network=none --shm-size=1g \
  --memory=4g --memory-swap=4g --cpus=4 $sandboxed --user \$(id -u):\$(id -g) -e HOME=/work/home -e SANDBOX=$SANDBOX -e DRAW=${DRAW:-} -e RENDER_API=${RENDER_API:-} \
  -v \$PWD:/work -v \$HOME/$RUNTIME:/opt/wpe-runtime:ro $RUN_IMAGE bash /work/linux-run.sh $MODE $ONLY" \
  >"$OUT/$MODE.log" 2>&1 || code=$?
rsync -a "desktop:$WORK/out/" "$OUT/"
grep -E '^CHECK |^CHECKS |^perf-lab: |^SHOT |^LAB |^DRAW |^webview-sample: ' "$OUT/$MODE.log" || true
echo "linux-checks: $MODE exited $code; logs in $OUT"
exit "$code"
