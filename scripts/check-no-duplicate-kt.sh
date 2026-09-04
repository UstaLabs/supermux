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
if [[ -n "$dups" ]]; then
  echo "Duplicate .kt basenames across app modules ($(echo "$dups" | wc -l)) — move them into apps/ui or apps/shared:"
  echo "$dups"
  exit 1
fi
echo "no duplicate .kt basenames across app modules"
