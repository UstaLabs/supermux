#!/usr/bin/env bash
# scripts/ios-sim-run.sh — build + run the iOS app in the Simulator on the remote Mac.
#   usage: scripts/ios-sim-run.sh [pair-token] [broker-base] [EXTRA_ENV_KEY=VAL ...]
#
# Headless edit→build→run→screenshot loop for design/feel work (see the
# `ios-simulator-on-remote-mac` skill). Ad-hoc SIGNED (not CODE_SIGNING_ALLOWED=NO):
# the app carries an App Group entitlement and traps on launch when it is stripped.
# Env reaches the app via SIMCTL_CHILD_* (`--env` is broken on Xcode 26).
set -euo pipefail

TOKEN="${1:-}"
BASE="${2:-http://100.84.92.82:9898}"
BUNDLE="dev.supermux.app"
REMOTE_ROOT='~/supermux-sim'
DD='build/dd-sim'

cd "$(git rev-parse --show-toplevel)"
step() { echo "==> $*"; }

step "[1/5] sync worktree -> mac:${REMOTE_ROOT}"
tar --exclude .git --exclude 'apps/*/build' --exclude 'apps/shared/build' \
    --exclude node_modules --exclude .gradle --exclude local.properties -czf - . \
  | ssh mac "rm -rf ${REMOTE_ROOT} && mkdir -p ${REMOTE_ROOT} && tar -xzf - -C ${REMOTE_ROOT}"

step "[2/5] xcodegen + xcodebuild (iOS Simulator, ad-hoc signed)"
# NO `-sdk iphonesimulator`: it forces the embedded watchOS target onto the iOS SDK too, and its
# asset catalog then fails ("AppIcon did not have any applicable content"). The destination alone
# routes each target to its own simulator SDK. ENABLE_DEBUG_DYLIB=NO drops the `__preview.dylib`
# link step, which fails for the ad-hoc-signed app extensions.
ssh mac "bash -lc 'source ~/ios-build-env.sh; cd ${REMOTE_ROOT}/apps/iosApp && xcodegen generate >/dev/null && \
  xcodebuild -scheme Supermux -configuration Debug \
    -destination \"generic/platform=iOS Simulator\" -derivedDataPath ${DD} \
    ARCHS=arm64 EXCLUDED_ARCHS=x86_64 ENABLE_DEBUG_DYLIB=NO \
    CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
    CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build 2>&1 \
    | grep -E \"error:|warning: .*(deprecat|unused)|BUILD (SUCCEEDED|FAILED)\" | head -40'"

step "[3/5] install on the booted simulator"
ssh mac "bash -lc 'UDID=\$(xcrun simctl list devices booted | grep -oE \"[0-9A-F-]{36}\" | head -1); \
  [ -z \"\$UDID\" ] && { echo \"no booted sim\"; exit 1; }; echo \"sim: \$UDID\"; \
  xcrun simctl install \$UDID ${REMOTE_ROOT}/apps/iosApp/${DD}/Build/Products/Debug-iphonesimulator/Supermux.app'"

step "[4/5] relaunch"
EXTRA=""
for kv in "${@:3}"; do EXTRA+=" SIMCTL_CHILD_${kv}"; done
ssh mac "bash -lc 'UDID=\$(xcrun simctl list devices booted | grep -oE \"[0-9A-F-]{36}\" | head -1); \
  xcrun simctl terminate \$UDID ${BUNDLE} 2>/dev/null || true; \
  xcrun simctl status_bar \$UDID override --time 9:41 --batteryLevel 100 --cellularBars 4 --wifiBars 3 2>/dev/null || true; \
  SIMCTL_CHILD_SM_PAIR_TOKEN=${TOKEN} SIMCTL_CHILD_SM_PAIR_BASE=${BASE}${EXTRA} xcrun simctl launch \$UDID ${BUNDLE}'"

step "[5/5] screenshot -> /tmp/sm-sim.png"
sleep 6
ssh mac "bash -lc 'UDID=\$(xcrun simctl list devices booted | grep -oE \"[0-9A-F-]{36}\" | head -1); \
  xcrun simctl io \$UDID screenshot ~/sm-sim.png'"
ssh mac 'cat ~/sm-sim.png' > /tmp/sm-sim.png
echo "screenshot: /tmp/sm-sim.png"
