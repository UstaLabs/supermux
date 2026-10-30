#!/usr/bin/env bash
# Inside webview-wpe-run:24.04 (linux-engine-checks.sh): Xvfb and openbox, then the sample on the WPE runtime mounted at
# /opt/wpe-runtime. /work is desktop's ~/work/webview-linux-checks: lib/ (the jars), out/ (logs, screenshots), home/.
#   linux-run.sh checks [name]   the engine checks: CHECK lines, then CHECKS DONE; exit 0 if all passed
#   linux-run.sh lab             the sample itself on WPE engines (Task 28)
# Docker gives bubblewrap no user namespaces, so WebKit's sandbox is off here (WpeRuntime's opt-out), unless SANDBOX=on:
# linux-engine-checks.sh then starts the container so that bubblewrap can make them.
set -euo pipefail
mode="${1:-checks}"
only="${2:-}"
mkdir -p /work/out "$HOME"
export DISPLAY=:99 NO_AT_BRIDGE=1 LIBGL_ALWAYS_SOFTWARE=1
Xvfb :99 -screen 0 1600x1200x24 -nolisten tcp +extension GLX +extension RANDR >/work/out/xvfb.log 2>&1 &
for _ in $(seq 1 100); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.1; done
xdpyinfo >/dev/null 2>&1 || { echo "linux-run: Xvfb did not start"; cat /work/out/xvfb.log; exit 3; }
openbox >/work/out/openbox.log 2>&1 &
sleep 1
props=(-Ddev.supermux.webview.wpe.runtime=/opt/wpe-runtime -Dsun.java2d.uiScale=1)
[ "${SANDBOX:-off}" = on ] || props+=(-Ddev.supermux.webview.wpe.sandbox=off)
case "$mode" in
  checks) props+=(-Dwebview.sample.checks=true); [ -n "$only" ] && props+=("-Dwebview.sample.only=$only") ;;
  lab) props+=(-Dwebview.sample.engine=wpe -Dwebview.sample.out=/work/out) ;;
  *) echo "linux-run: the mode is checks or lab"; exit 2 ;;
esac
exec java -Xms256m -Xmx2g -XX:+UseG1GC "${props[@]}" -cp '/work/lib/*' dev.supermux.webview.sample.MainKt
