#!/usr/bin/env bash
# The iOS shell cutover (cluster H6) is one-way: the Compose shell is the ONLY iOS shell.
#
# What this guards is not tidiness. The SwiftUI screens first moved to `SupermuxMacUI/` for the
# native macOS app, and when that app was retired they were deleted for good — the Compose
# Multiplatform desktop app (`apps/desktop`) is the only Mac client. Nothing in Xcode stops someone
# adding `apps/iosApp/Supermux/Shell/RootView.swift` back: the iOS target's source entry is the
# whole `Supermux` directory, so a re-added file is silently compiled into the phone app and the
# fork is open again. Likewise `COMPOSE_SHELL` — every `#if` on it is gone, so a new one would be a
# condition that is never defined and a branch that is silently always dead.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0

# 1. The superseded SwiftUI directories must not come back to the iOS app target.
for d in Shell Sessions Intro DesignSystem Display Editor Broker Chat/Composer; do
  p="apps/iosApp/Supermux/$d"
  if [ -e "$p" ]; then
    echo "FAIL: $p exists again — the SwiftUI shell is gone; screens belong in Compose (:ui)."
    fail=1
  fi
done

# 2. The retired native macOS app must not come back: no `SupermuxMacUI/` source tree and no
#    `SupermuxMac*` target in the XcodeGen spec. Its Xcode project was the only thing that could
#    compile the SwiftUI shell, so this is what makes check 1 more than a naming convention.
if [ -e apps/iosApp/SupermuxMacUI ]; then
  echo "FAIL: apps/iosApp/SupermuxMacUI exists again — the native macOS SwiftUI app was retired."
  fail=1
fi
if grep -nE '^  SupermuxMac[A-Za-z]*:' apps/iosApp/project.yml >/dev/null 2>&1; then
  echo "FAIL: project.yml declares a SupermuxMac target/scheme again:"
  grep -nE '^  SupermuxMac[A-Za-z]*:' apps/iosApp/project.yml
  fail=1
fi

# 3. The flag is gone for good. Matched as a USE — a preprocessor condition or an Xcode build
#    setting — so the H6 commentary explaining why the flag went away does not trip its own guard.
pat='^[[:space:]]*#(if|elseif).*COMPOSE_SHELL|^[[:space:]]*-[[:space:]]*COMPOSE_SHELL'
if grep -rnE --include='*.swift' --include='*.yml' "$pat" apps/iosApp >/dev/null 2>&1; then
  echo "FAIL: COMPOSE_SHELL is back:"
  grep -rnE --include='*.swift' --include='*.yml' "$pat" apps/iosApp
  fail=1
fi

# 4. A ceiling on the iOS app target's own Swift, so the shell cannot creep back file by file.
#    H6 left it at ~4.3k lines (from 28.4k); retiring the Mac app trimmed the `os(macOS)` halves
#    below that. The bound is deliberately loose — real work on the
#    surviving Swift (push, speech, the SwiftTerm host) should not trip it — but a re-added screen
#    would.
limit=8000
have=$(find apps/iosApp/Supermux -name '*.swift' -exec cat {} + | wc -l)
if [ "$have" -gt "$limit" ]; then
  echo "FAIL: the iOS app target is $have Swift lines, over the $limit ceiling."
  echo "      Cluster H6 cut it to ~4.3k. If a screen came back, it belongs in Compose (:ui);"
  echo "      if this is genuine growth in the surviving Swift, raise the ceiling deliberately."
  fail=1
fi

[ "$fail" = 0 ] && echo "iOS shell cutover intact: app target $have Swift lines, no COMPOSE_SHELL, no re-added SwiftUI dirs, no Mac target."
exit $fail
