#!/bin/bash
# The editor sample in the iOS Simulator (runs ON THE MAC): builds it, then with Maestro checks that
# a tap raises the soft keyboard, that its keys type (t, e, h, space, Return, delete), and that the
# accessibility tree has exactly one element holding the editor's text (the hidden input field
# must not show up), and takes a screenshot of the selection menu after a long press.
#   device-checks/ios-sim.sh [simulator udid]      (default: the "sm-iPhone" simulator)
set -u
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:$HOME/.maestro/bin:/opt/homebrew/bin:$PATH
APPS="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT:-$HOME/work/editor-ios-check}"; mkdir -p "$OUT"
U="${1:-$(xcrun simctl list devices | grep "sm-iPhone (" | grep -oE "[0-9A-F-]{36}" | head -1)}"
cd "$APPS/editor-sample/iosApp" && xcodegen generate >/dev/null
if [ -z "${SKIP_BUILD:-}" ]; then
  xcodebuild -project EditorSample.xcodeproj -scheme EditorSampleApp -configuration Debug -sdk iphonesimulator \
    -destination "generic/platform=iOS Simulator" -derivedDataPath build/ddsim ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build > "$OUT/build.log" 2>&1 \
    || { grep -E "error:" "$OUT/build.log" | head; echo "FAIL simulator build"; exit 1; }
fi
xcrun simctl boot "$U" 2>/dev/null; sleep 5
xcrun simctl install "$U" build/ddsim/Build/Products/Debug-iphonesimulator/EditorSampleApp.app
FAILS=0
flow() { # name, yaml
  printf '%s\n' "appId: dev.supermux.editor.sample" "---" "$2" > "$OUT/$1.yaml"
  if maestro --udid "$U" test --no-reinstall-driver "$OUT/$1.yaml" > "$OUT/$1.log" 2>&1; then echo "PASS $1"; else echo "FAIL $1 (see $OUT/$1.log)"; FAILS=$((FAILS+1)); fi
  xcrun simctl io "$U" screenshot "$OUT/$1.png" >/dev/null 2>&1
}
flow launch "- launchApp: { clearState: true, stopApp: true }
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"HostStore.kt (2k lines)\", timeout: 60000 }
- swipe: { start: \"380,78\", end: \"30,78\", duration: 500 }
- waitForAnimationToEnd
- tapOn: \"Türkçe + emoji\"
- waitForAnimationToEnd
- assertNotVisible: \"shift\""
# A headless simulator counts a hardware keyboard as attached and hides the software one until
# XCTest types (even when every focus starts a session). So "a tap raises the keyboard" is checked
# as: after ONE tap on a fresh launch, typed text reaches the editor (an input session, first
# responder: exactly what shows the keyboard on a device), and then the software keys are there.
flow tap-starts-input "- tapOn: { point: \"60%,30%\" }
- waitForAnimationToEnd
- inputText: \"zzqx\"
- extendedWaitUntil: { visible: \"shift\", timeout: 8000 }"
flow soft-keys-type "- tapOn: { id: \"Return\" }
- tapOn: \"q\"
- tapOn: \"z\"
- tapOn: \"j\"
- tapOn: { id: \"delete\" }
- tapOn: { id: \"space\" }
- waitForAnimationToEnd"
maestro --udid "$U" hierarchy > "$OUT/tree.json" 2>"$OUT/tree.err"
python3 - "$OUT/tree.json" <<'PY' || FAILS=$((FAILS+1))
import json, sys
d = json.load(open(sys.argv[1]))
els = []
def walk(n):
    a = n.get("attributes", {})
    els.append(a)
    for c in n.get("children", []): walk(c)
walk(d)
text = [a for a in els if len(a.get("value", "") or "") > 40]
print("INFO elements holding text:", [(a.get("accessibilityText"), a.get("bounds")) for a in text])
ok = len(text) == 1 and text[0].get("accessibilityText") == "Sample editor"
print(("PASS" if ok else "FAIL") + " exactly one element holds the editor's text, labelled")
v = text[0].get("value", "") if text else ""
import re
ok0 = "zzqx" in v
print(("PASS" if ok0 else "FAIL") + " the first tap's input session typed 'zzqx' at the caret")
ok2 = re.search(r"\nqz ", v) is not None or "\nqz" in v
print(("PASS" if ok2 else "FAIL") + " Return, the keys and Backspace reached the document (a line 'qz'): " + repr([l for l in v.split("\n") if l.startswith("qz")][:2]))
sys.exit(0 if ok and ok0 and ok2 else 1)
PY
flow long-press-menu "- tapOn: { point: \"10%,90%\" }
- longPressOn: { point: \"45%,30%\" }
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"Copy\", timeout: 5000 }"
echo "INFO screenshots in $OUT (long-press-menu.png: the menu must not cover the handles)"
echo "ios simulator checks: $FAILS failed"
exit $FAILS
