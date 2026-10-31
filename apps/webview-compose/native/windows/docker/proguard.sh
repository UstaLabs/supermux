#!/usr/bin/env bash
# ProGuard 7.8.0 as Compose 1.12.2 runs it, on the configuration its proguardReleaseJars wrote (its own runner fails on
# Gradle 9.7 after writing it: the macOS release check's finding), with the jars from the helper's Gradle cache.
# Inside the build image on desktop, /w = desktop's ~/work/wvc:   proguard.sh ROOT_CONFIG
# ProGuard writes where the configuration's -outjars say (jars-config.pro, next to it); that folder is printed last.
set -euo pipefail
CONFIG="$1"
CACHE=/w/gradle-home/caches/modules-2/files-2.1
jar() {
  local found; found="$(find "$CACHE/$1" -name '*.jar' ! -name '*-sources.jar' 2>/dev/null | head -1)"
  [ -n "$found" ] || { echo "proguard.sh: no $1 in the Gradle cache (proguardReleaseJars resolves it)" >&2; exit 1; }
  echo "$found"
}
# The runner's classpath: Compose 1.12.2's `proguard` configuration (each found or the run stops, one at a time).
CP=""
for artifact in com.guardsquare/proguard-base/7.8.0 com.guardsquare/proguard-core/9.2.0 \
  org.jetbrains.kotlin/kotlin-metadata-jvm/2.2.0 org.jetbrains.kotlin/kotlin-stdlib/2.2.0 \
  org.apache.logging.log4j/log4j-api/2.24.2 org.apache.logging.log4j/log4j-core/2.24.2 com.google.code.gson/gson/2.11.0; do
  found="$(jar "$artifact")"
  CP="${CP:+$CP:}$found"
done
OUT="$(sed -n "s/^-outjars '\(.*\)\/[^/]*'$/\1/p" "$(dirname "$CONFIG")/jars-config.pro" | head -1)"
[ -n "$OUT" ] || { echo "proguard.sh: no -outjars in jars-config.pro" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT"
"${JAVA:-/opt/java/openjdk/bin/java}" -cp "$CP" proguard.ProGuard "@$CONFIG" > "$OUT.log" 2>&1 || { tail -30 "$OUT.log" >&2; exit 1; }
grep -E '^(Warning|Note): there were' "$OUT.log" >&2 || true
echo "proguard.sh: $(find "$OUT" -name '*.jar' | wc -l) shrunk jars, $(du -sh "$OUT" | cut -f1)" >&2
echo "$OUT"
