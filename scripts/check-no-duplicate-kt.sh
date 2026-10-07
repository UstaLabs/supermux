#!/usr/bin/env bash
# Fails when the same .kt basename exists in more than one APP module (android/desktop/ios), or in
# an app module AND `apps/ui` — a file left behind beside its moved twin in `:ui` is the same
# copy-adapted-instead-of-moved mistake, one step further along. (`apps/ui` vs `apps/shared` is NOT
# a duplicate: those are LAYERS — `proto/GitBadge.kt` is the data, `ui/session/GitBadge.kt` draws
# it — and the same basename there is the split working as designed.)
#
# Also prints the KMP sharing ratio (shared+ui commonMain lines vs app-module main lines).
# BLOCKING in CI since cluster G8 — see .github/workflows/ci.yml. The conceptual-twin list the
# spec's cluster table drove is retired: it reached 0 in G8 and an empty list is a dead gate, so
# the widened basename scan below is the whole check now.
set -euo pipefail
cd "$(dirname "$0")/.."

# App modules — the original scan.
mods=()
for m in apps/android/src/main apps/desktop/src/main apps/ios/src; do [[ -d "$m" ]] && mods+=("$m"); done
# ...plus `apps/ui`, so a screen left behind in an app module beside its shared twin counts.
roots=("${mods[@]}")
[[ -d apps/ui/src/commonMain ]] && roots+=(apps/ui/src/commonMain)

# `apps/ui` holds expect/actual pairs by design (commonMain + jvmMain/androidMain), so only its
# commonMain is scanned, and each module is counted ONCE per basename before the comparison.
# TEST source sets are out of scope: a suite may legitimately keep a host-specific name beside the
# shared one it was split from.
claim() { # $1 = root → "<basename> <module>"
  local module=$1
  find "$module" -name '*.kt' -not -path '*/build/*' -printf '%f\n' | sort -u | sed "s|\$| $module|"
}
all=""
for r in "${roots[@]}"; do all+="$(claim "$r")"$'\n'; done
dups=$(printf '%s' "$all" | grep -v '^$' | awk '{print $1}' | sort | uniq -d)

shared=$(find apps/shared/src/commonMain apps/ui/src/commonMain -name '*.kt' -exec cat {} + | wc -l)
apps=$(find "${mods[@]}" -name '*.kt' -exec cat {} + | wc -l)
echo "kmp sharing: shared=$shared app-specific=$apps ratio=$(( shared * 100 / (shared + apps) ))%"

if [[ -n "$dups" ]]; then
  echo "Duplicate .kt basenames across modules ($(echo "$dups" | wc -l)) — move them into apps/ui or apps/shared:"
  while read -r name; do
    [[ -z "$name" ]] && continue
    echo "  $name:$(printf '%s' "$all" | awk -v n="$name" '$1==n {printf " %s", $2}')"
  done <<< "$dups"
  exit 1
fi
echo "no duplicate .kt basenames across modules"
