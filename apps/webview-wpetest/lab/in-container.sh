#!/usr/bin/env bash
# Runs inside the lab container (lab.sh run): an X server and a window manager for skiko, then the test app with one
# scenario. /lab is the host's work folder: lab/, lib/ (the jars), native/out/libwvlab.so, results/, home/.
#   in-container.sh SCENARIO [JAVA_PROPS...]
set -euo pipefail
scenario="$1"; shift
mkdir -p /lab/results "$HOME"
export DISPLAY=:99 NO_AT_BRIDGE=1
# Software GL unless the run has the host's GPU (lab.sh LAB_GPU=1); Xvfb's own GLX is software either way.
[ -n "${LAB_GPU:-}" ] || export LIBGL_ALWAYS_SOFTWARE=1
Xvfb :99 -screen 0 1920x1200x24 -nolisten tcp +extension GLX +extension RANDR >/lab/results/xvfb.log 2>&1 &
xvfb=$!
for _ in $(seq 1 100); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.1; done
xdpyinfo >/dev/null 2>&1 || { echo "in-container: Xvfb did not start"; cat /lab/results/xvfb.log; exit 3; }
openbox >/lab/results/openbox.log 2>&1 &
sleep 1
stamp="$(date -u +%Y%m%d-%H%M%S)"
log="/lab/results/$scenario-$stamp.log"
set +e
java -Xms256m -Xmx1g -XX:+UseG1GC \
  -Dwpe.scenario="$scenario" -Dwpe.out=/lab/results -Dwpe.stamp="$stamp" -Dwpe.host="${LAB_HOST_NAME:-unknown}" \
  -Dwpe.native=/lab/native/out/libwvlab.so "$@" \
  -cp '/lab/lib/*' dev.supermux.webview.wpetest.MainKt >"$log" 2>&1
code=$?
set -e
kill "$xvfb" 2>/dev/null || true
grep -h '^WPE ' "$log" >"/lab/results/$scenario-$stamp.txt" || true
echo "in-container: $scenario exit $code, $(wc -l <"/lab/results/$scenario-$stamp.txt") result lines, log $log"
exit "$code"
