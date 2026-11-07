#!/usr/bin/env bash
# The macOS engine in a release build of the desktop sample (review I5), ON THE MAC:
#   shrunk   bash apps/webview-sample/device-checks/mac-release-check.sh shrunk
#            after `webview-mac-gradle -Pcompose.desktop.packaging.checkJdkVendor=false :webview-sample:proguardReleaseJars`
#   packaged bash apps/webview-sample/device-checks/mac-release-check.sh packaged
#            after `webview-mac-gradle -Pcompose.desktop.packaging.checkJdkVendor=false :webview-sample:createDistributable`
#
# shrunk: the app's jars shrunk by ProGuard as Compose's release build shrinks them: the configuration the plugin wrote
# for proguardReleaseJars (the app's jars, Compose's default rules, the library's keep rules), run by the ProGuard the
# plugin uses (7.8.0, from Gradle's cache). On this repository's Gradle 9.7, Compose 1.12.2's own runner fails before
# ProGuard runs ("getStandardOutput(...) must not be null"): the configuration is written first, so it is run here.
# packaged: the app Compose packages (jpackage, its runtime bundled, the glue's dylib re-signed in its jar), run by its
# own launcher. Either way, a few checks that need the glue's call back: an engine made, a page committed, the bridge
# both ways, a first paint, a press on the page (mac-engine-checks.sh, in the logged-in session).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE="$(cd "$HERE/.." && pwd)"
MODE="${1:?usage: mac-release-check.sh shrunk|packaged}"
JAVA=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
CHECKS="factory-makes-a-native-view-engine,html-commits-on-its-secure-origin,the-bridge-delivers-to-the-current-document"
CHECKS="$CHECKS,first-paint-waits-for-the-screen,mac-the-page-takes-its-presses-and-a-marked-overlay-its-own"

# jar <group/artifact/version>: that artifact's jar in Gradle's cache.
jar() {
  find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' 2>/dev/null | head -1
}

case "$MODE" in
  shrunk)
    CONFIG="$SAMPLE/build/compose/tmp/proguardReleaseJars/root-config.pro"
    [[ -f "$CONFIG" ]] || { echo "mac-release-check.sh: no $CONFIG: run proguardReleaseJars first" >&2; exit 1; }
    OUT_DIR="$(sed -n "s/^-outjars '\\(.*\\)\\/[^/]*'$/\\1/p" "$SAMPLE/build/compose/tmp/proguardReleaseJars/jars-config.pro" | head -1)"
    CP="$(jar com.guardsquare/proguard-base/7.8.0):$(jar com.guardsquare/proguard-core/9.2.0)"
    CP="$CP:$(jar org.jetbrains.kotlin/kotlin-metadata-jvm/2.2.0):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.2.0)"
    CP="$CP:$(jar org.apache.logging.log4j/log4j-api/2.24.2):$(jar org.apache.logging.log4j/log4j-core/2.24.2)"
    CP="$CP:$(jar com.google.code.gson/gson/2.11.0)"
    rm -rf "$OUT_DIR"
    mkdir -p "$OUT_DIR"
    echo "mac-release-check.sh: ProGuard 7.8.0 on $CONFIG" >&2
    "$JAVA" -cp "$CP" proguard.ProGuard "@$CONFIG" > "$OUT_DIR.log" 2>&1 || { tail -30 "$OUT_DIR.log" >&2; exit 1; }
    grep -E "^(Warning|Note): there were" "$OUT_DIR.log" >&2 || true
    echo "mac-release-check.sh: $(find "$OUT_DIR" -name '*.jar' | wc -l | tr -d ' ') shrunk jars, $(du -sh "$OUT_DIR" | cut -f1)" >&2
    LIB="$OUT_DIR" bash "$HERE/mac-engine-checks.sh" "$CHECKS"
    ;;
  packaged)
    APP="$(find "$SAMPLE/build/compose/binaries" -maxdepth 3 -name '*.app' -type d 2>/dev/null | head -1)"
    [[ -n "$APP" ]] || { echo "mac-release-check.sh: no app under $SAMPLE/build/compose/binaries: run createDistributable first" >&2; exit 1; }
    APP="$APP" bash "$HERE/mac-engine-checks.sh" "$CHECKS"
    ;;
  *)
    echo "mac-release-check.sh: unknown mode $MODE" >&2
    exit 2
    ;;
esac
