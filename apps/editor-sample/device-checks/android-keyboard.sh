#!/bin/bash
# The soft keyboard on Android, on the Mac's emulator (or any adb serial): a tap raises it, a finger
# scroll does not, and a second tap brings back a keyboard the user dismissed; then text typed
# through the IME (not key events) reaches the document.
#   device-checks/android-keyboard.sh [apk] [serial]     (runs ON THE MAC; boots the read-only AVD if no serial)
# Only dev.supermux.editor.sample is installed; nothing else on the device is touched.
set -u
SDK="$HOME/Library/Android/sdk"; ADB="$SDK/platform-tools/adb"
APK="${1:-$(cd "$(dirname "$0")/.." && pwd)/build/outputs/apk/debug/editor-sample-debug.apk}"
S="${2:-}"
BOOTED=""
if [ -z "$S" ]; then
  if ! "$ADB" devices | grep -q "^emulator-5554"; then
    ( nohup "$SDK/emulator/emulator" -avd supermux_emu -no-window -read-only -no-snapshot -no-audio -memory 2048 >/tmp/editor-kb-emu.log 2>&1 & )
    BOOTED=1
  fi
  S=emulator-5554
fi
A() { "$ADB" -s "$S" "$@"; }
A wait-for-device
until [ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
A install -r "$APK" >/dev/null || { echo "FAIL install"; exit 1; }
A shell am force-stop dev.supermux.editor.sample
A shell am start -n dev.supermux.editor.sample/.MainActivity >/dev/null
sleep 12
# A "System UI isn't responding" dialog on a freshly booted, loaded emulator eats the first tap:
# press its "Wait".
A shell input keyevent KEYCODE_WAKEUP
anr() { A shell uiautomator dump /sdcard/editor-kb.xml >/dev/null 2>&1; A shell cat /sdcard/editor-kb.xml | grep -q "isn't responding"; }
for i in 1 2 3; do
  if anr; then
    B=$(A shell cat /sdcard/editor-kb.xml | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '\[[0-9]*,[0-9]*\]' | head -1 | tr -d '[]')
    A shell input tap ${B%,*} ${B#*,}; sleep 3
  fi
done
shown() { A shell dumpsys input_method | grep -o "mInputShown=[a-z]*" | head -1 | cut -d= -f2; }
FAILS=0
check() { if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1 (got $2, want $3)"; FAILS=$((FAILS+1)); fi; }
W=$(A shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1); WX=${W%x*}; WY=${W#*x}
X=$((WX/2)); Y=$((WY/3))
check "no keyboard before a tap" "$(shown)" false
A shell input swipe $X $((WY*2/3)) $X $((WY/3)) 300; sleep 2
check "a finger scroll does not raise the keyboard" "$(shown)" false
A shell input tap $X $Y; sleep 3
check "a tap raises the keyboard" "$(shown)" true
A shell input keyevent KEYCODE_BACK; sleep 2
check "Back dismisses it" "$(shown)" false
A shell input tap $X $((Y+40)); sleep 3
check "a second tap brings it back" "$(shown)" true
# The accessory bar (a soft keyboard's missing keys): it needs a keyboard with a real height, so a
# visual IME (not the ADB keyboard) for this part. Its keys run editor commands and never take the
# focus: after them the keyboard is still up; its last key hides the keyboard.
VIS_IME=$(A shell ime list -s | tr -d '\r' | grep -v adbkeyboard | head -1)
bounds() { A shell uiautomator dump /sdcard/editor-kb.xml >/dev/null 2>&1; A shell cat /sdcard/editor-kb.xml | grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | grep -o '\[[0-9]*,[0-9]*\]' | head -2 | tr -d '[]' | tr '\n' ' '; }
tapdesc() { set -- $(bounds "$1"); [ -n "${1:-}" ] || return 1; A shell input tap $(( (${1%,*} + ${2%,*}) / 2 )) $(( (${1#*,} + ${2#*,}) / 2 )); }
if [ -n "$VIS_IME" ]; then
  # A headless emulator counts a hardware keyboard: Gboard shows only with this setting.
  A shell settings put secure show_ime_with_hard_keyboard 1
  A shell ime set "$VIS_IME" >/dev/null; sleep 2
  # (No Back here: with the keyboard already gone it would close the app.)
  A shell input tap $X $Y; sleep 4
  [ "$(shown)" = true ] || { A shell input tap $X $((Y+40)); sleep 4; }
  check "the accessory bar shows with the soft keyboard" "$( [ -n "$(bounds "Move right")" ] && echo yes || echo no)" yes
  for k in "Move right" "Move right" "Move down" "Tab" "Undo"; do tapdesc "$k"; sleep 1; done
  check "after its keys the keyboard is still up (the field kept the focus)" "$(shown)" true
  tapdesc "Hide keyboard"; sleep 2
  check "its hide-keyboard key hides the keyboard" "$(shown)" false
  check "and the bar goes with it" "$( [ -n "$(bounds "Move right")" ] && echo yes || echo no)" no
  A shell input tap $X $Y; sleep 3
  check "a tap brings keyboard and bar back" "$(shown)" true
else
  echo "SKIP accessory bar: no visual IME on this device"
fi
# Text through the IME (InputConnection.commitText), not key events: the ADB keyboard IME
# (com.android.adbkeyboard, the AVD's current IME) commits a broadcast's text. Turkish, then Return
# and a Backspace through the IME's key path.
if A shell ime list -s | grep -q adbkeyboard; then
  A shell ime set com.android.adbkeyboard/.AdbIME >/dev/null
  A shell am broadcast -a ADB_INPUT_TEXT --es msg "ğüşıöçİ" >/dev/null; sleep 2
  A shell am broadcast -a ADB_INPUT_CODE --ei code 66 >/dev/null; sleep 1   # Return
  A shell am broadcast -a ADB_INPUT_TEXT --es msg "zq" >/dev/null; sleep 1
  A shell am broadcast -a ADB_INPUT_CODE --ei code 67 >/dev/null; sleep 2   # Backspace
  A exec-out screencap -p > /tmp/editor-kb-typed.png
  echo "INFO typed ğüşıöçİ, Return, zq, Backspace through the IME: see /tmp/editor-kb-typed.png (expect 'ğüşıöçİ' then a new line with 'z')"
else
  echo "SKIP IME typing: the ADB keyboard IME is not installed"
fi
[ -n "$BOOTED" ] && A emu kill >/dev/null 2>&1
echo "android keyboard checks: $FAILS failed"
exit $FAILS
