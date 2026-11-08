#!/usr/bin/env bash
# The Windows engine in a release build of the desktop sample (macOS review I5; Linux review I9), from THIS BOX:
#   shrunk     ProGuard on the sample's release configuration (Docker on desktop); the shrunk jars on the VM; checks
#   unkept     the same without the WinGlue keep rule: the engine must be Unavailable with a detail naming the rule
#   packaged   jpackage --type app-image on the VM from the shrunk jars and the JBR; the app's launcher runs the checks
#   cache      the DLL's cache: hash-named, replaced when tampered with; two instances at once; the slots' locks (V3)
# Each mode stages what it needs, so they run in any order: its Docker work on desktop first, then the VM (one heavy thing
# at a time there; run it with the VM shut off). SKIP_RELEASE=1 reuses the shrunk jars already in C:\wvc\lib-release.
# Exit status: 0 when the mode passed. KEEP_UP=1 leaves a VM this run started running; WVC_JAVA_HOME as winvm.sh's (here
# the image's Temurin 21 unless set).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE="$(cd "$HERE/../../webview-compose/native/windows" && pwd)"
VMH="$NATIVE/winvm.sh"
JUMP=ahmet@100.104.43.81
MODE="${1:?usage: windows-release-check.sh shrunk|unkept|packaged|cache}"
# Compose's ProGuard configuration lists the build JDK's jmods as library jars: the image's JDK 17 is a headless package
# with none, its Temurin 21 has them.
export WVC_JAVA_HOME="${WVC_JAVA_HOME:-/opt/java/openjdk}"
TREE="$(git -C "$HERE" rev-parse --show-toplevel | sha1sum | cut -c1-8)"   # the helper's name for this worktree
APP=/w/trees/$TREE/apps
TMP=/w/trees/$TREE/apps/webview-sample/build/compose/tmp/proguardReleaseJars
CHECKS="factory-makes-a-frames-engine,html-commits-on-its-secure-origin,the-bridge-delivers-to-the-current-document"
CHECKS="$CHECKS,first-paint-waits-for-the-screen,a-click-reaches-the-page,an-app-shortcut-reaches-the-app-and-not-the-page"
CHECKS="$CHECKS,only-page-navigations-ask-the-client"
DOMAIN=supermux-windows
[ "${VM:-cpu}" != gpu ] || DOMAIN=supermux-windows-gpu
started=""
finish() { if [ -n "$started" ] && [ -z "${KEEP_UP:-}" ]; then "$VMH" down; fi; }
trap finish EXIT

# The VM, started if it is shut off (this run then shuts it down at the end, unless KEEP_UP).
up() {
  # This domain's own state: the state line names the other domain too.
  if "$VMH" state | grep -q "^winvm: $DOMAIN is shut off"; then started=1; fi
  "$VMH" up
}

# The script on stdin, in the build image on desktop, capped as the helper's Gradle is. Its $ are the container's.
in_image() {
  ssh -o BatchMode=yes "$JUMP" "cd work/wvc && docker run --rm -i --init --name wvc-release-$$ --memory=3g --memory-swap=3g \
    --cpus=3 --user \$(id -u):\$(id -g) -v \$PWD:/w ${WVC_IMAGE:-wvc-build:1} bash -s"
}

# release kept|unkept: the shrunk jars (Docker on desktop), then the VM up and the jars staged as C:\wvc\lib-release.
release() {
  if [ -n "${SKIP_RELEASE:-}" ]; then up; return 0; fi
  # Compose's own ProGuard runner fails on Gradle 9.7 after writing its configuration: the configuration is what counts.
  "$VMH" gradle -Pwebview.sample.windows -Pcompose.desktop.packaging.checkJdkVendor=false :webview-sample:proguardReleaseJars || true
  local config=$TMP/root-config.pro
  if [ "$1" = unkept ]; then
    # Compose includes the library's rules file by path: point that -include at a copy without the WinGlue rule. Each
    # line fails the run if it finds nothing to change.
    in_image <<EOF
set -euo pipefail
R=$APP/webview-compose/src/jvmMain/resources/META-INF/proguard/webview-compose-desktop.pro
grep -v '^-keep class dev.supermux.webview.engine.windows.WinGlue ' "\$R" > $TMP/rules-unkept.pro
test \$(( \$(wc -l < "\$R") - \$(wc -l < $TMP/rules-unkept.pro) )) -eq 1
sed "s#\$R#$TMP/rules-unkept.pro#" $config > $TMP/root-config-unkept.pro
grep -q rules-unkept.pro $TMP/root-config-unkept.pro
EOF
    config=$TMP/root-config-unkept.pro
  fi
  local out; out="$(echo "bash $APP/webview-compose/native/windows/docker/proguard.sh $config" | in_image | tail -1)"
  up
  "$VMH" stage-release "${out#/w/trees/$TREE/apps/webview-sample/}"
}

case "$MODE" in
  shrunk) release kept; WVC_LIB=lib-release "$VMH" checks "$CHECKS" ;;
  unkept)
    release unkept
    log="$(WVC_LIB=lib-release "$VMH" checks factory-makes-a-frames-engine || true)"
    echo "$log"
    grep -q 'CHECK FAIL factory-makes-a-frames-engine: .*NativeLibraryFailed.*-keep class dev.supermux.webview.engine.windows.WinGlue' <<<"$log"
    ;;
  packaged)
    release kept
    "$VMH" ps "$NATIVE/vm/package-sample.ps1"
    WVC_LAUNCHER='C:\wvc\app\webview-sample\webview-sample.exe' "$VMH" checks "$CHECKS"
    ;;
  cache) release kept; "$VMH" ui "$NATIVE/vm/cache-check.ps1" 900 | tee /dev/stderr | grep -q '^CACHE DONE failed=0' ;;
  *) echo "windows-release-check.sh: unknown mode $MODE" >&2; exit 2 ;;
esac
