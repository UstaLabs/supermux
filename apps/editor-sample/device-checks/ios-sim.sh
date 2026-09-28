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
# The app runs with its console captured: an uncaught Kotlin exception fails the check.
xcrun simctl terminate "$U" dev.supermux.editor.sample 2>/dev/null
( xcrun simctl launch --console-pty --terminate-running-process "$U" dev.supermux.editor.sample > "$OUT/console.log" 2>&1 & )
sleep 5
# (The toolbar grew with M4c's LSP chips and M4d's files: swipe it until the file's chip shows.)
flow launch "- waitForAnimationToEnd
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"settings\", timeout: 60000 }
- repeat:
    while: { notVisible: \"Türkçe + emoji\" }
    times: 8
    commands:
      - swipe: { start: \"380,78\", end: \"100,78\", duration: 500 }
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
# The Smart Punctuation shim depends on Compose-internal class names: after an input session it must
# have patched the focused input view, all three traits answering .no (EditorDiagnostics, status line).
flow smart-punctuation-shim "- extendedWaitUntil: { visible: \".*smart punctuation: off.*\", timeout: 8000 }"
flow soft-keys-type "- tapOn: { id: \"Return\" }
- tapOn: \"q\"
- tapOn: \"z\"
- tapOn: \"j\"
- tapOn: { id: \"delete\" }
- tapOn: { id: \"space\" }
- waitForAnimationToEnd"
# The accessory bar (M4b task 3): with the software keyboard up, its keys run editor commands and
# never take the focus: the keyboard is still there after them; its last key hides the keyboard.
# (A headless simulator shows the software keyboard again only after typing: "a", then erased.)
flow accessory-bar "- extendedWaitUntil: { visible: \"Move right\", timeout: 8000 }
- tapOn: \"Move right\"
- tapOn: \"Move right\"
- tapOn: \"Tab\"
- tapOn: \"Shift-Tab\"
- waitForAnimationToEnd
- assertVisible: \"shift\"
- swipe: { start: \"70%,59%\", end: \"10%,59%\", duration: 400 }
- waitForAnimationToEnd
- tapOn: \"Move down\"
- waitForAnimationToEnd
- assertVisible: \"shift\"
- tapOn: \"Hide keyboard\"
- waitForAnimationToEnd
- assertNotVisible: \"shift\"
- assertNotVisible: \"Move right\"
- tapOn: { point: \"50%,90%\" }
- waitForAnimationToEnd
- inputText: \"a\"
- extendedWaitUntil: { visible: \"shift\", timeout: 8000 }
- eraseText: 1"
# Smart Punctuation off: the keyboard's " key types U+0022 and closing brackets pair it ("" with the
# cursor between), never “ ”.
flow straight-quotes "- tapOn: { point: \"200,450\" }
- waitForAnimationToEnd
- tapOn: { id: \"more\" }
- tapOn: \"\\\"\"
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
ok3 = '\n""' in v
curly = any(ch in v for ch in "\u201c\u201d\u2018\u2019")
print(("PASS" if ok3 and not curly else "FAIL") + " the \" key typed U+0022 and paired to \"\" (no curly quotes): " + repr([l for l in v.split("\n") if '"' in l or "\u201c" in l or "\u201d" in l][:2]))
sys.exit(0 if ok and ok0 and ok2 and ok3 and not curly else 1)
PY
flow long-press-menu "- tapOn: { point: \"200,720\" }
- longPressOn: { point: \"120,138\" }
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"Copy\", timeout: 5000 }"
# The iPhone crash: a long press on an empty line (to paste there) gives a caret and Paste, and never
# reads the clipboard (a read shows iOS's paste permission prompt).
printf 'from another app' | xcrun simctl pbcopy "$U"
flow long-press-empty-line "- tapOn: { point: \"200,720\" }
- longPressOn: { point: \"200,121\" }
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"Paste\", timeout: 5000 }
- assertNotVisible: \"Allow Paste\""
# The iPhone crash: with the keyboard up, a long press on an empty line, just past a line's end and
# right on the caret reached the hidden field's own touch selection (Compose's moveCaretByLongPress
# with -1 threw). Now the editor handles each: a caret and Paste, no exception.
flow long-press-empty-line-kb "- tapOn: { point: \"200,121\" }
- waitForAnimationToEnd
- longPressOn: { point: \"200,121\" }
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"Paste\", timeout: 5000 }"
flow long-press-line-end-kb "- tapOn: { point: \"380,103\" }
- waitForAnimationToEnd
- longPressOn: { point: \"385,103\" }
- waitForAnimationToEnd"
flow long-press-on-caret "- tapOn: { point: \"120,138\" }
- waitForAnimationToEnd
- longPressOn: { point: \"120,138\" }
- waitForAnimationToEnd"
# The menu's Paste reads the clipboard inside iOS's own paste action: no permission prompt.
printf 'MENUPASTE' | xcrun simctl pbcopy "$U"
flow menu-paste-no-prompt "- tapOn: { point: \"200,121\" }
- waitForAnimationToEnd
- longPressOn: { point: \"200,121\" }
- waitForAnimationToEnd
- tapOn: \"Paste\"
- waitForAnimationToEnd
- assertNotVisible: \"Allow Paste\"
- extendedWaitUntil: { visible: \".*MENUPASTE.*\", timeout: 5000 }"
# A plain Compose text field of the app keeps the user's Smart Punctuation (the editor turns it off
# only for itself): its " key types a curly quote.
flow plain-field-keeps-smart-quotes "- repeat:
    while: { notVisible: \"settings\" }
    times: 8
    commands:
      - swipe: { start: \"30,78\", end: \"310,78\", duration: 500 }
      - waitForAnimationToEnd
- tapOn: \"settings\"
- waitForAnimationToEnd
- tapOn: \"plain field\"
- waitForAnimationToEnd
- inputText: \"a \"
- tapOn: { id: \"more\" }
- tapOn: \"\\\"\"
- waitForAnimationToEnd"
maestro --udid "$U" hierarchy > "$OUT/tree2.json" 2>/dev/null
python3 - "$OUT/tree2.json" <<'PY' || FAILS=$((FAILS+1))
import json, sys
d = json.load(open(sys.argv[1]))
vals = []
def walk(n):
    a = n.get("attributes", {})
    if a.get("accessibilityText") == "plain field": vals.append(a.get("value") or a.get("text") or "")
    for c in n.get("children", []): walk(c)
walk(d)
v = vals[0] if vals else ""
ok = "\u201c" in v or "\u201d" in v
print(("PASS" if ok else "FAIL") + " a plain Compose text field keeps smart quotes: " + repr(v))
sys.exit(0 if ok else 1)
PY
# The space-bar trackpad (floating cursor): UIKit's calls on the focused input view move the editor's
# caret through the editor's layout (Compose alone would use the 1 dp hidden field's). Maestro can't
# long-press the space bar, so the sample's probe sends UIKit's own calls: 3 lines down, text intact.
flow floating-cursor "- tapOn: { point: \"200,138\" }
- waitForAnimationToEnd
- tapOn: \"floating cursor drag\"
- extendedWaitUntil: { visible: \"float: moved down .*, text unchanged, mapped\", timeout: 5000 }"
# A text field INSIDE a block widget (the M3c demo's review thread): its own keyboard and typing, the
# editor untouched; a tap on the code gives the editor its input back.
flow widget-text-field "- tapOn: \"close\"
- waitForAnimationToEnd
- repeat:
    while: { notVisible: \"M3c demo: gutter, fold, thread, panel\" }
    times: 8
    commands:
      - swipe: { start: \"380,78\", end: \"100,78\", duration: 500 }
      - waitForAnimationToEnd
- tapOn: \"M3c demo: gutter, fold, thread, panel\"
- waitForAnimationToEnd
- extendedWaitUntil: { visible: \"reply field\", timeout: 30000 }
- tapOn: \"reply field\"
- waitForAnimationToEnd
- inputText: \"from the widget\"
- waitForAnimationToEnd
- tapOn: { point: \"70%,20%\" }
- waitForAnimationToEnd
- inputText: \"EDX\"
- waitForAnimationToEnd"
maestro --udid "$U" hierarchy > "$OUT/tree3.json" 2>/dev/null
python3 - "$OUT/tree3.json" <<'PY' || FAILS=$((FAILS+1))
import json, sys
d = json.load(open(sys.argv[1]))
reply, editor = [], []
def walk(n):
    a = n.get("attributes", {})
    if a.get("accessibilityText") == "reply field": reply.append(a.get("value") or a.get("text") or "")
    if a.get("accessibilityText") == "Sample editor": editor.append(a.get("value") or "")
    for c in n.get("children", []): walk(c)
walk(d)
r = reply[0] if reply else ""
e = editor[0] if editor else ""
ok = "from the widget" in r and "EDX" not in r and "from the widget" not in e and "EDX" in e
print(("PASS" if ok else "FAIL") + " a widget's text field types on its own, the editor gets its input back: reply=" + repr(r) + " editor has EDX=" + repr("EDX" in e))
sys.exit(0 if ok else 1)
PY
sleep 2
if xcrun simctl spawn "$U" launchctl list | grep -q editor.sample; then echo "PASS still running after the long presses"; else echo "FAIL the app is gone"; FAILS=$((FAILS+1)); fi
if grep -q "Uncaught Kotlin exception" "$OUT/console.log"; then echo "FAIL an uncaught Kotlin exception:"; grep -A4 "Uncaught Kotlin exception" "$OUT/console.log" | head -8; FAILS=$((FAILS+1)); else echo "PASS no uncaught Kotlin exception in the console"; fi
echo "INFO screenshots in $OUT (long-press-menu.png: the menu must not cover the handles)"
echo "ios simulator checks: $FAILS failed"
exit $FAILS
