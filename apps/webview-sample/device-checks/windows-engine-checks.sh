#!/usr/bin/env bash
# The Windows engine's checks (sample/windows/WindowsChecks.kt), in the sample on the VM supermux-windows (VM=gpu: its GPU
# copy), through the library's VM helper. Runs ON THIS BOX: the jars build in Docker on desktop, the DLLs on the VM.
#   device-checks/windows-engine-checks.sh [name,...]   every check, or those named
#   SKIP_BUILD=1 reuses the DLLs fetched last; SKIP_STAGE=1 the jars staged last; KEEP_UP=1 leaves the VM running
# Exit status: 0 when every check passed. The VM is shut down at the end if this run started it, as it was found.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VMH="$(cd "$HERE/../../webview-compose/native/windows" && pwd)/winvm.sh"
DOMAIN=supermux-windows
[ "${VM:-cpu}" != gpu ] || DOMAIN=supermux-windows-gpu
started=""
# This domain's own state: the state line names the other domain too.
if "$VMH" state | grep -q "^winvm: $DOMAIN is shut off"; then started=1; fi
"$VMH" up
code=0
{
  [ -n "${SKIP_BUILD:-}" ] || "$VMH" build
  [ -n "${SKIP_STAGE:-}" ] || "$VMH" stage-sample
  "$VMH" checks "${1:-}"
} || code=$?
if [ -n "$started" ] && [ -z "${KEEP_UP:-}" ]; then "$VMH" down; fi
exit "$code"
