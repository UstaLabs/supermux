#!/usr/bin/env bash
# Inside webview-wpe-run:24.04 (linux-engine-checks.sh): Xvfb and openbox, then the sample on the WPE runtime mounted at
# /opt/wpe-runtime. /work is desktop's ~/work/webview-linux-checks: lib/ (the jars), out/ (logs, screenshots), home/.
#   linux-run.sh checks [name]   the engine checks: CHECK lines, then CHECKS DONE; exit 0 if all passed
#   linux-run.sh lab             the sample itself on WPE engines (Task 28)
#   linux-run.sh draw            the draw lab: DRAW=<views>x<width>x<height> (default 6x1600x1200) spinner pages, side by
#                                side, and the UI thread's cost per frame; RENDER_API=SOFTWARE draws without GL
# Docker gives bubblewrap no user namespaces, so WebKit's sandbox is off here (WpeRuntime's opt-out), unless SANDBOX=on:
# linux-engine-checks.sh then starts the container so that bubblewrap can make them.
set -euo pipefail
mode="${1:-checks}"
only="${2:-}"
mkdir -p /work/out "$HOME"
export DISPLAY=:99 NO_AT_BRIDGE=1 LIBGL_ALWAYS_SOFTWARE=1
screen=1600x1200
[ "$mode" = draw ] && screen="${DRAW_SCREEN:-4800x2400}"
Xvfb :99 -screen 0 ${screen}x24 -nolisten tcp +extension GLX +extension RANDR >/work/out/xvfb.log 2>&1 &
for _ in $(seq 1 100); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.1; done
xdpyinfo >/dev/null 2>&1 || { echo "linux-run: Xvfb did not start"; cat /work/out/xvfb.log; exit 3; }
openbox >/work/out/openbox.log 2>&1 &
sleep 1
# user.home is the mounted home: Java takes the image's ubuntu user's otherwise, gone with the container.
props=(-Dsun.java2d.uiScale=1 "-Duser.home=$HOME")
# The runtime as a folder, or as an archive the engine unpacks into the home (ARCHIVE, linux-engine-checks.sh).
if [ -n "${ARCHIVE:-}" ]; then props+=("-Ddev.supermux.webview.wpe.archive=$ARCHIVE"); else props+=(-Ddev.supermux.webview.wpe.runtime=/opt/wpe-runtime); fi
[ "${SANDBOX:-off}" = on ] || props+=(-Ddev.supermux.webview.wpe.sandbox=off)
case "$mode" in
  checks) props+=(-Dwebview.sample.checks=true); [ -n "$only" ] && props+=("-Dwebview.sample.only=$only") ;;
  lab) props+=(-Dwebview.sample.engine=wpe -Dwebview.sample.out=/work/out) ;;
  draw) props+=("-Dwebview.sample.draw=${DRAW:-6x1600x1200}") ;;
  *) echo "linux-run: the mode is checks, lab or draw"; exit 2 ;;
esac
[ -n "${RENDER_API:-}" ] && props+=("-Dskiko.renderApi=$RENDER_API")
exec java -Xms256m -Xmx2g -XX:+UseG1GC "${props[@]}" -cp '/work/lib/*' dev.supermux.webview.sample.MainKt
