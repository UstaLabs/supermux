#!/usr/bin/env bash
# Checks the publication guards of :terminal-core — the rules that decide whether an artifact may
# be produced at all. It drives the real Gradle build (there is no cheaper way to test a build
# script's configuration logic) and asserts the outcome of four scenarios:
#
#   1. a -dev version, no profile property            -> dev profile, host gate, succeeds
#   2. a RELEASE version with -Pterminal.publishProfile=dev
#                                                     -> REFUSED (the lenient gate may not be
#                                                        forced onto release coordinates)
#   3. a RELEASE version, default profile             -> release gate, fails here (no Mac targets)
#   4. a RELEASE version + the escape hatch           -> succeeds, and the ABI manifest says
#                                                        incomplete_release: true with the
#                                                        missing targets
#
# Usage:  bash apps/terminal-core/scripts/check-publish-guard.sh
# Takes a few minutes: each scenario is a Gradle configuration (+ a manifest task for 1 and 4).
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
apps="$(cd "$here/../.." && pwd)"
manifest="$apps/terminal-core/build/gradle/generated/packageMetadata/dev/supermux/terminal/abi-manifest.json"
gradle=(nice "$apps/gradlew" --no-daemon -Dorg.gradle.jvmargs=-Xmx2048m
        -Dkotlin.compiler.execution.strategy=in-process --max-workers=2 -p "$apps")
failures=0

run() { # run <expect-success|expect-failure> <name> <gradle args...>
  local expect="$1" name="$2"; shift 2
  local log; log="$(mktemp)"
  echo "--- $name"
  if "${gradle[@]}" "$@" >"$log" 2>&1; then outcome=success; else outcome=failure; fi
  if [[ "$outcome" == "${expect#expect-}" ]]; then
    echo "    OK ($outcome, as expected)"
  else
    echo "    FAILED: expected ${expect#expect-}, got $outcome"; tail -25 "$log"; failures=$((failures + 1))
  fi
  LAST_LOG="$log"
}

grep_log() { # grep_log <description> <pattern>
  if grep -qF "$2" "$LAST_LOG"; then echo "    OK (log says: $1)"
  else echo "    FAILED: log does not contain \"$2\" ($1)"; failures=$((failures + 1)); fi
}

json_is() { # json_is <jq-ish python expr> <expected>
  local got; got="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print($2)" "$manifest")"
  if [[ "$got" == "$3" ]]; then echo "    OK ($1 = $got)"
  else echo "    FAILED: $1 = $got, expected $3"; failures=$((failures + 1)); fi
}

# 1. the normal dev publish path
run expect-success "1. -dev version, default profile -> host gate" \
  :terminal-core:verifyNativeArtifactsForHost :terminal-core:generateAbiManifest
grep_log "host gate satisfied" "'dev' profile satisfied"
json_is "profile" "d['profile']" dev
json_is "incomplete_release" "d['incomplete_release']" False

# 2. THE guard: a release number may not take the lenient gate
run expect-failure "2. release version + -Pterminal.publishProfile=dev -> refused" \
  -Pterminal.version=1.0.0 -Pterminal.publishProfile=dev :terminal-core:generateAbiManifest
grep_log "refusal names the version" "refusing the 'dev' publication profile for version '1.0.0'"

# 3. a release number gets the release gate, which is not satisfiable on one host
run expect-failure "3. release version, default profile -> release gate fails" \
  -Pterminal.version=1.0.0 :terminal-core:verifyNativeArtifacts
grep_log "release gate names a Mac-only target" "ios-arm64: lib/libsupermux_terminal.a or manifest.json missing"

# 4. the escape hatch: allowed, but the build shouts and the artifact says so itself
run expect-success "4. release version + escape hatch -> allowed, stamped" \
  -Pterminal.version=1.0.0 -Pterminal.publishProfile=dev \
  -Pterminal.allowIncompleteReleasePublish=true :terminal-core:generateAbiManifest
grep_log "warning banner" "INCOMPLETE RELEASE PUBLISH"
json_is "version" "d['version']" 1.0.0
json_is "incomplete_release" "d['incomplete_release']" True
json_is "incomplete_targets" "','.join(d['incomplete_targets'])" "ios-arm64,ios-simulator-arm64,macos-arm64,macos-x64"

# Leave the generated manifest back in its normal (dev) state.
run expect-success "5. regenerate the dev manifest" :terminal-core:generateAbiManifest
json_is "version" "d['version']" 0.1.0-dev.1

echo
if ((failures)); then echo "PUBLISH GUARD CHECK: $failures failure(s)"; exit 1; fi
echo "PUBLISH GUARD CHECK PASSED"
