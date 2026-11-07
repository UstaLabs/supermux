#!/usr/bin/env bash
# The Linux engine with WebKit's page sandbox ON, outside any container, on `desktop` (LinuxSandboxCheck.java): the
# engine's natives on a WPE runtime there, one page, its frame, and its web process in bubblewrap's sandbox. Runs ON
# THIS BOX; the engine's jar comes from the jars linux-engine-checks.sh staged (build/linux/lib).
#   device-checks/linux-sandbox-check.sh
#   RUNTIME=<dir on desktop>   the runtime (default work/wpe-runtime/out/wpe-runtime-2.54.2-linux-x64)
#   WORK=<dir on desktop>      where the check runs (default work/webview-linux-sandbox)
#   EXTRA_LIBS=<dir on desktop> libraries the machine lacks, on LD_LIBRARY_PATH (desktop has no libgstgl)
#   BUS=keep                   keep the D-Bus session: only where /usr/bin/xdg-dbus-proxy is installed
#   SANDBOX=off                the same page with the sandbox off; SANDBOX=damage instead checks that WebKit's damage
#                              at scale 2 is in buffer pixels (the feasibility test's S5, again)
# WebKit starts xdg-dbus-proxy whenever its page could reach a D-Bus session or accessibility bus, and aborts without
# it. desktop has none, and no root to install it, so by default the check hides the session from WebKit: no
# DBUS_SESSION_BUS_ADDRESS or AT_SPI_BUS_ADDRESS, and an XDG_RUNTIME_DIR of its own (GLib finds the bus there too).
# Exit status: 0 passed, 1 failed, 134 WebKit aborted the JVM.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(dirname "$HERE")"
RUNTIME="${RUNTIME:-work/wpe-runtime/out/wpe-runtime-2.54.2-linux-x64}"
WORK="${WORK:-work/webview-linux-sandbox}"
SSHO=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=15)
die() { echo "linux-sandbox: $*" >&2; exit 1; }
d() { ssh "${SSHO[@]}" desktop "$1"; }

timeout 20 ssh "${SSHO[@]}" desktop true 2>/dev/null || die "desktop doesn't answer"
jars=("$MODULE"/build/linux/lib/webview-compose-jvm-*.jar "$MODULE"/build/linux/lib/kotlin-stdlib-[0-9]*.jar)
for jar in "${jars[@]}"; do [ -f "$jar" ] || die "no $jar: run linux-engine-checks.sh first, or its stage"; done
d "mkdir -p $WORK/lib && rm -f $WORK/lib/*.jar"
rsync -a "${jars[@]}" "desktop:$WORK/lib/"
rsync -a "$HERE/LinuxSandboxCheck.java" "desktop:$WORK/"
env=(env HOME="\$HOME")
if [ "${BUS:-hide}" != keep ]; then
  env=(env -u DBUS_SESSION_BUS_ADDRESS -u AT_SPI_BUS_ADDRESS XDG_RUNTIME_DIR="\$HOME/$WORK/run")
fi
[ -n "${EXTRA_LIBS:-}" ] && env+=(LD_LIBRARY_PATH="\$HOME/$EXTRA_LIBS")
code=0
d "cd $WORK && rm -rf run && mkdir -m 700 run && ${env[*]} java -cp 'lib/*' LinuxSandboxCheck.java \$HOME/$RUNTIME ${SANDBOX:-on}" || code=$?
echo "linux-sandbox: exited $code"
exit "$code"
