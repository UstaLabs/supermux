#!/usr/bin/env bash
# Fails when the same .kt basename exists in more than one app module (android/desktop/ios).
# A duplicate almost always means a screen was copy-adapted instead of moved into apps/ui or apps/shared.
# Also prints the KMP sharing ratio (shared+ui commonMain lines vs app-module main lines).
set -euo pipefail
cd "$(dirname "$0")/.."
mods=()
for m in apps/android/src/main apps/desktop/src/main apps/ios/src; do [[ -d "$m" ]] && mods+=("$m"); done
dups=$(find "${mods[@]}" -name '*.kt' -printf '%f\n' | sort | uniq -d)
shared=$(find apps/shared/src/commonMain apps/ui/src/commonMain -name '*.kt' -exec cat {} + | wc -l)
apps=$(find "${mods[@]}" -name '*.kt' -exec cat {} + | wc -l)
echo "kmp sharing: shared=$shared app-specific=$apps ratio=$(( shared * 100 / (shared + apps) ))%"
# Conceptual twins: same screen under different names on the two apps (from
# docs/superpowers/specs/2026-09-04-screens-into-ui-design.md). Non-blocking; each pair
# disappears when the screen moves into apps/ui. Format: android-file|desktop-file(s).
twins=(
  "session/SessionListScreen.kt|session/SessionListPanel.kt shell/WorkspaceListPanel.kt"
  "settings/MoreScreens.kt|settings/SettingsHub.kt settings/DevicesSettingsScreen.kt settings/ProxiesSettingsScreen.kt settings/CuratorSettingsScreen.kt settings/PersonalAssistantsScreen.kt usage/UsageScreen.kt session/ArchivedScreen.kt"
  "settings/VoiceSettingsScreens.kt|settings/VoiceSettingsScreen.kt"
  "chat/FinishSheet.kt|chat/FinishDialog.kt"
  "pairing/OnboardingIntro.kt|intro/FirstRunIntro.kt"
  "workspace/AndroidViewHost.kt|shell/ViewHost.kt"
  "workspace/WorkspaceScreen.kt|shell/AppShell.kt"
  "chat/ChatScreen.kt|chat/DesktopComposer.kt chat/ComposerFooter.kt"
  "terminal/TerminalPanel.kt|terminal/DesktopTerminalPanel.kt"
  "update/AppUpdateScreen.kt|update/AppUpdateUi.kt"
)
left=0
for pair in "${twins[@]}"; do
  a="apps/android/src/main/kotlin/dev/supermux/android/${pair%%|*}"
  ds=${pair#*|}; present=""
  for d in $ds; do [[ -f "apps/desktop/src/main/kotlin/dev/supermux/desktop/$d" ]] && present="$present $d"; done
  if [[ -f "$a" && -n "$present" ]]; then left=$((left+1)); echo "  twin: ${pair%%|*} <->$present"; fi
done
echo "conceptual screen twins still split across apps: $left (non-blocking)"
if [[ -n "$dups" ]]; then
  echo "Duplicate .kt basenames across app modules ($(echo "$dups" | wc -l)) — move them into apps/ui or apps/shared:"
  echo "$dups"
  exit 1
fi
echo "no duplicate .kt basenames across app modules"
