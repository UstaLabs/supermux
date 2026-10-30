#!/usr/bin/env bash
# Drives the Windows engine's work (docs/superpowers/plans/2026-10-30-webview-windows-engine.md) from this box: Gradle in
# Docker on `desktop`, and a Windows 11 x64 VM there (libvirt) for the DLL, its tests and the sample's checks. Every
# command is safe to repeat: a session cut mid-command runs the same command again.
#
#   winvm.sh state                the VM's state, whether this helper started it, the guest's uptime and free disk
#   winvm.sh up                   start the VM if it is shut off, and remember that this helper did; wait for SSH + console
#   winvm.sh adopt                take over a running VM the user started for this helper (then `down` may stop it)
#   winvm.sh down                 shut down a VM this helper started or adopted; leave any other alone
#   winvm.sh setup                check the guest's tools and make C:\wvc (vm/setup.ps1); installs nothing
#   winvm.sh build [x64|arm64]    copy native/windows to C:\wvc\native, build there, run glue_test.exe, fetch the DLLs
#   winvm.sh test                 run glue_test.exe again
#   winvm.sh gradle TASK...       Gradle in Docker on desktop, on a copy of this worktree's apps/ and the fetched DLLs
#   winvm.sh stage-sample         the sample for Windows x64 (stageWindows), mirrored from desktop to C:\wvc\lib
#   winvm.sh checks [NAMES]       the sample's engine checks on tester's desktop (NAMES: comma-separated); exit 0 if all pass
#   winvm.sh lab [PROPS]          the sample's perf lab on WebView2 engines, unattended
#   winvm.sh results              copy the result files this box doesn't have yet (after a cut session)
#   winvm.sh ps FILE.ps1          run a script in the SSH session (elevated, session 0: no desktop)
#   winvm.sh ui FILE.ps1 [SECS]   run a script on tester's desktop through a scheduled task of this run
#   winvm.sh wake                 wake the guest's display (Right Ctrl; never Shift: five open Sticky Keys)
#
# VM=gpu drives supermux-windows-gpu (the same disk, desktop's GTX 1660 SUPER passed through) instead of
# supermux-windows: for the perf lab's GPU column and the GPU-specific checks only, each time with the user's go-ahead
# (WVC_GPU_GO=yes). The user stops the host's GPU users first (their sudo); `adopt` takes over a GPU domain the user
# started. After `up` the desktop moves to the card (gpu-display.ps1 gpu, then single), and back to virtio before
# `down`, so supermux-windows boots as it expects. One domain at a time (one disk). supermux-win-fresh is never touched.
set -euo pipefail

JUMP=ahmet@100.104.43.81
case "${VM:-cpu}" in
  cpu) DOMAIN=supermux-windows; OTHER=supermux-windows-gpu ;;
  gpu) DOMAIN=supermux-windows-gpu; OTHER=supermux-windows ;;
  *) echo "winvm: VM is cpu or gpu" >&2; exit 2 ;;
esac
VIRSH="virsh -c qemu:///system"
WORK=work/wvc                                  # on desktop, relative to ahmet's home
MARK="$WORK/vm-started-by-wvc-$DOMAIN"         # present while this helper owns the running VM
IMAGE="${WVC_IMAGE:-supermux-build}"           # desktop:~/work/Dockerfile.supermux-build (JDK 17 + 21); never rebuilt here
RUN="${WVC_RUN:-$(date -u +%Y%m%d-%H%M%S)-$$}" # names this run's scheduled task and logs
HSSHO=(-o BatchMode=yes -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=15)
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # apps/webview-compose/native/windows
MODULE="$(cd "$HERE/../.." && pwd)"                      # apps/webview-compose
APPS="$(dirname "$MODULE")"
SAMPLE="$APPS/webview-sample"
TREE="$(git -C "$APPS" rev-parse --show-toplevel | sha1sum | cut -c1-8)"
SRC="$WORK/trees/$TREE/apps"                   # this worktree's copy on desktop

die() { echo "winvm: $*" >&2; exit 1; }
host() { ssh "${HSSHO[@]}" "$JUMP" "$@"; }
domstate() { host "$VIRSH domstate $1" 2>/dev/null | head -1 | tr -d '\r'; }

[ "$DOMAIN" != supermux-win-fresh ] || die "never supermux-win-fresh"

# Both domains boot the same disk with the same NIC MAC, so the guest has the same address in either (W21).
guest_ip() { echo 192.168.122.242; }

# The GPU domain only with the user's go-ahead for this run (the coordinator's rule; the card is the host's otherwise).
gpu_go() {
  [ "$DOMAIN" != supermux-windows-gpu ] || [ "${WVC_GPU_GO:-}" = yes ] ||
    die "VM=gpu needs the user's go-ahead for this run (then WVC_GPU_GO=yes)"
}

# Every command that uses the guest: only a VM this helper started or adopted (another session's is left alone).
require_ours() {
  [ "$(domstate "$DOMAIN")" = running ] || die "$DOMAIN is not running: winvm.sh up"
  host "test -f $MARK" || die "$DOMAIN is running but isn't this helper's: another session's, or adopt it if the user started it for you"
}

# The host drivers of the PCI devices the domain passes through (empty for supermux-windows): the wintest helper's.
hostdev_drivers() {
  host "bash -s" <<EOF
for a in \$($VIRSH dumpxml $DOMAIN | sed -n "/<hostdev/,/<\/hostdev>/s/.*<address domain='0x\([0-9a-f]*\)' bus='0x\([0-9a-f]*\)' slot='0x\([0-9a-f]*\)' function='0x\([0-9a-f]*\)'.*/\1:\2:\3.\4/p"); do
  echo "\$a \$(basename "\$(readlink /sys/bus/pci/devices/\$a/driver)")"
done
EOF
}

# The GPU domain's desktop on the card (W21): the module's display switch, elevated, then tester's single display,
# then DXGI's view of it (gpu-probe, in tester's session: session 0 lists no outputs). Idempotent.
gpu_display() {
  local mode="$1" job; job="$(mktemp --suffix=.ps1)"
  guest 'New-Item -ItemType Directory -Force C:\wv2test\vm | Out-Null'
  gscp "$APPS/webview-wintest/vm/gpu-display.ps1" "$APPS/webview-wintest/vm/gpu-probe.ps1" "$APPS/webview-wintest/vm/gpu-probe.cpp" "$(vm):C:/wv2test/vm/"
  echo "& C:\wv2test\vm\gpu-display.ps1 -Mode $mode" | run_ps
  [ "$mode" = gpu ] || return 0
  echo "& C:\wv2test\vm\gpu-display.ps1 -Mode single" > "$job"; cmd_ui "$job" 120
  # gpu-probe lists each DXGI adapter, then its outputs: an NVIDIA adapter must have the desktop's output.
  echo "& C:\wv2test\vm\gpu-probe.ps1" > "$job"
  cmd_ui "$job" 300 | tee /dev/stderr | awk '/^  \[[0-9]+\]/ {nv = /NVIDIA/} nv && /output [0-9]+:.*desktop=1/ {ok = 1} END {exit !ok}' ||
    { rm -f "$job"; die "DXGI shows no desktop output on the card: look at gpu-display.ps1 status"; }
  rm -f "$job"
}
VM_SSH=""
vm() { [ -n "$VM_SSH" ] || VM_SSH="tester@$(guest_ip)"; echo "$VM_SSH"; }
# The guest's own options: ssh keeps the first value of a repeated -o, so these can't extend HSSHO.
GSSHO=(-o BatchMode=yes -o LogLevel=ERROR -o ConnectTimeout=40 -o ServerAliveInterval=15
  -o StrictHostKeyChecking=accept-new -J "$JUMP")

# Retries a guest ssh/scp whose connection was never set up (a busy guest's sshd: Defender, updates). Nothing ran on the
# guest then, so the retry is safe. Any other failure is returned as it is.
retry_conn() {
  local attempt rc err
  err="$(mktemp)"
  for attempt in 1 2 3 4; do
    rc=0
    "$@" 2>"$err" || rc=$?
    if [ "$rc" -eq 255 ] && grep -qiE 'banner exchange|kex_exchange_identification|Connection timed out|Connection refused|Connection closed by [0-9]' "$err"; then
      sleep $((attempt * 5)); continue
    fi
    break
  done
  cat "$err" >&2; rm -f "$err"
  return "$rc"
}
guest() { retry_conn ssh "${GSSHO[@]}" "$(vm)" "$@"; }
gscp() { retry_conn scp -q "${GSSHO[@]}" "$@"; }
ssh_ok() { timeout 50 ssh "${GSSHO[@]}" "$(vm)" hostname >/dev/null 2>&1; }
wait_ssh() { local i; for i in $(seq 1 "$1"); do VM_SSH=""; ssh_ok 2>/dev/null && return 0; sleep 5; done; return 1; }

# Runs "$@" with an ssh-agent holding this box's key, so `ssh -A` lets desktop reach the guest for bulk copies.
with_agent() {
  if ssh-add -l >/dev/null 2>&1; then "$@"; return; fi
  local sock rc=0
  sock="$(mktemp -u "${TMPDIR:-/tmp}/wvc-agent.XXXXXX")"
  eval "$(ssh-agent -s -a "$sock")" >/dev/null
  ssh-add -q "$HOME/.ssh/id_ed25519"
  "$@" || rc=$?
  ssh-agent -k >/dev/null 2>&1 || true
  return "$rc"
}

# Runs the PowerShell script on stdin in the SSH session and prints its output.
run_ps() {
  local tmp name; tmp="$(mktemp --suffix=.ps1)"; cat > "$tmp"
  name="ssh-$RUN-$RANDOM.ps1"
  guest 'New-Item -ItemType Directory -Force C:\wvc\ui | Out-Null'
  gscp "$tmp" "$(vm):C:/wvc/ui/$name"
  rm -f "$tmp"
  local rc=0
  guest "powershell -NoProfile -ExecutionPolicy Bypass -File C:\\wvc\\ui\\$name; Remove-Item C:\\wvc\\ui\\$name" || rc=$?
  return "$rc"
}

cmd_state() {
  local state; state="$(domstate "$DOMAIN")"
  echo "winvm: $DOMAIN is ${state:-unknown}; $(host "test -f $MARK && echo \"started by this helper at \$(cat $MARK)\" || echo 'not started by this helper'"); $OTHER is $(domstate "$OTHER")"
  [ "$state" = running ] && ssh_ok || return 0
  run_ps <<'PS'
$boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
"winvm: up since $($boot.ToString('yyyy-MM-dd HH:mm')) guest time; C: free {0:N1} GB" -f ((Get-Volume -DriveLetter C).SizeRemaining / 1GB)
# Scheduled tasks a cut session left: this helper's, and not running.
Get-ScheduledTask -TaskName 'wvc-ui-*' -ErrorAction SilentlyContinue | Where-Object { $_.State -ne 'Running' } |
    ForEach-Object { Unregister-ScheduledTask -TaskName $_.TaskName -Confirm:$false; "winvm: removed a stale task $($_.TaskName)" }
PS
}

wait_console() {
  local i
  for i in $(seq 1 36); do
    if guest quser 2>/dev/null | grep -qi 'console.*active'; then return 0; fi
    sleep 5
  done
  return 1
}

cmd_up() {
  gpu_go
  [ "$(domstate "$OTHER")" != running ] || die "$OTHER is running: one Windows VM at a time (one disk, 15 GB)"
  local state; state="$(domstate "$DOMAIN")"
  case "$state" in
    running) host "test -f $MARK" || die "$DOMAIN is running and this helper did not start it: another session may be using it (adopt it if the user started it for you)" ;;
    "shut off")
      local free; free="$(host "awk '/MemAvailable/ {print int(\$2/1024)}' /proc/meminfo")"
      [ "${free:-0}" -ge 9000 ] || die "desktop has $free MB available; the VM needs 8 GB and Gradle more: is something else running?"
      if [ -n "$(hostdev_drivers)" ]; then
        # libvirt takes the card from the host's drivers at start; a process holding it can hang that unbind.
        local users; users="$(host 'echo $(( $(systemctl is-active comfyui ollama 2>/dev/null | grep -cx active) + $(nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null | grep -c "^[0-9]") ))')"
        [ "${users:-1}" -eq 0 ] || die "the host still uses the GPU (ComfyUI, Ollama or a CUDA process); the user stops them: ssh -t $JUMP 'sudo systemctl stop comfyui ollama'"
      fi
      echo "winvm: starting $DOMAIN on desktop"
      host "mkdir -p $WORK && $VIRSH start $DOMAIN >/dev/null && date -u +%FT%TZ > $MARK"
      ;;
    *) die "$DOMAIN is '${state:-unknown}': look at it (virsh on desktop) before starting it" ;;
  esac
  wait_ssh 72 || die "no SSH after 6 min; look at the console (VNC on desktop)"
  wait_console || die "SSH works but nobody is signed in on the console: the checks need tester's desktop"
  if [ "$DOMAIN" = supermux-windows-gpu ]; then gpu_display gpu; fi
  cmd_state
}

# The user started the domain (the GPU one needs their sudo to free the card first) and hands it over: mark it as ours.
cmd_adopt() {
  gpu_go
  [ "$(domstate "$DOMAIN")" = running ] || die "$DOMAIN is not running: nothing to adopt"
  host "mkdir -p $WORK && { test -f $MARK || echo \"adopted \$(date -u +%FT%TZ)\" > $MARK; }"
  wait_ssh 12 || die "$DOMAIN runs but its SSH doesn't answer"
  wait_console || die "SSH works but nobody is signed in on the console"
  if [ "$DOMAIN" = supermux-windows-gpu ]; then gpu_display gpu; fi
  cmd_state
}

cmd_down() {
  local state; state="$(domstate "$DOMAIN")"
  if [ "$state" = "shut off" ]; then host "rm -f $MARK"; echo "winvm: $DOMAIN already shut off"; return 0; fi
  host "test -f $MARK" || die "$DOMAIN is running and this helper did not start it: leaving it alone"
  # The disk is shared: supermux-windows boots it on its virtio display, so the GPU domain gives that back first.
  if [ "$DOMAIN" = supermux-windows-gpu ]; then gpu_display virtio || echo "winvm: gpu-display.ps1 virtio failed: run it before supermux-windows boots"; fi
  guest 'shutdown /s /t 5' >/dev/null 2>&1 || host "$VIRSH shutdown $DOMAIN >/dev/null" || true
  local i
  for i in $(seq 1 48); do
    if [ "$(domstate "$DOMAIN")" = "shut off" ]; then
      host "rm -f $MARK"; echo "winvm: $DOMAIN shut off"
      # After a GPU run the card goes back to the host's driver (managed='yes'); the host's GPU users stay stopped
      # until the user starts them again.
      hostdev_drivers | sed 's/^/winvm: host device /'
      return 0
    fi
    sleep 5
  done
  echo "winvm: still '$(domstate "$DOMAIN")' after 4 min; forcing it off (virsh destroy)"
  host "$VIRSH destroy $DOMAIN >/dev/null"; host "rm -f $MARK"
  hostdev_drivers | sed 's/^/winvm: host device /'
}

cmd_setup() {
  gscp "$HERE/vm/setup.ps1" "$(vm):C:/wvc-setup.ps1" 2>/dev/null || { guest 'New-Item -ItemType Directory -Force C:\wvc | Out-Null'; gscp "$HERE/vm/setup.ps1" "$(vm):C:/wvc-setup.ps1"; }
  guest 'powershell -NoProfile -ExecutionPolicy Bypass -File C:\wvc-setup.ps1; Remove-Item C:\wvc-setup.ps1'
}

cmd_build() {
  local arch="${1:-x64}" top tests testing
  case "$arch" in x64 | arm64) ;; *) die "build: x64 or arm64, not '$arch'" ;; esac
  run_ps >/dev/null <<'PS'
New-Item -ItemType Directory -Force C:\wvc\native\test, C:\wvc\native\testing | Out-Null
# Sources only: a file deleted here must not linger there. Built output (out\) stays until build.cmd replaces it.
Get-ChildItem C:\wvc\native -Recurse -File -Include *.h, *.cpp, *.cmd | Remove-Item -Force
PS
  # Only what exists: before Task 4's headers there is no *.h, before Task 5 no *.cpp here, before Task 27 no testing/.
  shopt -s nullglob
  top=("$HERE"/*.h "$HERE"/*.cpp "$HERE/build.cmd")
  tests=("$HERE"/test/*.cpp)
  testing=("$HERE"/testing/*.h "$HERE"/testing/*.cpp)
  shopt -u nullglob
  gscp "${top[@]}" "$(vm):C:/wvc/native/"
  if [ "${#tests[@]}" -gt 0 ]; then gscp "${tests[@]}" "$(vm):C:/wvc/native/test/"; fi
  if [ "${#testing[@]}" -gt 0 ]; then gscp "${testing[@]}" "$(vm):C:/wvc/native/testing/"; fi
  guest "cmd /c C:\\wvc\\native\\build.cmd $arch"
  [ -f "$HERE/jni.cpp" ] || { echo "winvm: tests only (no jni.cpp yet): nothing to fetch"; return 0; }
  mkdir -p "$MODULE/build/native/windows-$arch" "$SAMPLE/build/native/windows-$arch"
  gscp "$(vm):C:/wvc/native/out/$arch/webview_windows.dll" "$MODULE/build/native/windows-$arch/"
  if [ -f "$HERE/testing/testing_jni.cpp" ]; then
    gscp "$(vm):C:/wvc/native/out/$arch/webview_windows_testing.dll" "$SAMPLE/build/native/windows-$arch/"
  fi
  echo "winvm: fetched $(sha256sum "$MODULE/build/native/windows-$arch/webview_windows.dll" | cut -c1-16)… webview_windows.dll ($arch)"
  if [ "$arch" = x64 ]; then cmd_test; fi
}

cmd_test() { guest 'C:\wvc\native\out\x64\glue_test.exe'; }

# Gradle never runs on this box (short on RAM) nor on the Mac (overloaded): in the supermux-build image on desktop, JDK
# 17, its own Gradle home, capped, and only with 3.5 GB available (other agents and the VM use desktop too).
cmd_gradle() {
  [ "$#" -gt 0 ] || die "gradle needs at least one task"
  host "mkdir -p $SRC $WORK/gradle-home $WORK/home"
  rsync -az --delete -e "ssh ${HSSHO[*]}" \
    --exclude build --exclude .gradle --exclude .kotlin --exclude node_modules --exclude local.properties \
    --exclude kotlin-js-store --exclude 'desktop/resources/*/*' \
    "$APPS/" "$JUMP:$SRC/"
  # The DLLs fetched from the VM live under build/, which the copy above leaves out: they go on their own.
  local d
  for d in webview-compose/build/native webview-sample/build/native; do
    if [ -d "$APPS/$d" ]; then
      host "mkdir -p $SRC/$d"
      rsync -az --delete -e "ssh ${HSSHO[*]}" "$APPS/$d/" "$JUMP:$SRC/$d/"
    fi
  done
  host "cat > $WORK/gradle-home/gradle.properties" <<'PROPS'
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m
kotlin.daemon.jvmargs=-Xmx1g
kotlin.compiler.execution.strategy=in-process
org.gradle.workers.max=3
org.gradle.daemon=false
PROPS
  local free; free="$(host "awk '/MemAvailable/ {print int(\$2/1024)}' /proc/meminfo")"
  [ "${free:-0}" -ge 3500 ] || die "desktop has only ${free} MB available; Gradle needs 3 GB (the VM or another agent?)"
  host "cd $WORK && docker run --rm --init --name wvc-gradle-$RUN --memory=3g --memory-swap=3g --cpus=3 \
    --user \$(id -u):\$(id -g) -e HOME=/w/home -e GRADLE_USER_HOME=/w/gradle-home \
    -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 -e JAVA_TOOL_OPTIONS=-Duser.home=/w/home \
    -v \$PWD:/w -w /w/trees/$TREE/apps $IMAGE ./gradlew --no-daemon --console=plain $*"
}

# Mirrors desktop's build/windows/lib into C:\wvc\lib: copied to lib.new, then swapped in, so a cut copy never leaves a
# half classpath. Runs on desktop with this box's agent forwarded (the guest authorizes only this box's key).
mirror_lib() {
  local G; G="$(vm)"
  ssh -A "${HSSHO[@]}" "$JUMP" bash -s <<EOF
set -euo pipefail
O="-o BatchMode=yes -o LogLevel=ERROR -o StrictHostKeyChecking=accept-new"
# -n: without it the first ssh reads the rest of this script from stdin, and nothing after it runs.
G="ssh -n \$O $G"
\$G 'Remove-Item -Recurse -Force C:\wvc\lib.new -ErrorAction SilentlyContinue; New-Item -ItemType Directory -Force C:\wvc\lib.new | Out-Null'
scp -q \$O $SRC/webview-sample/build/windows/lib/* $G:C:/wvc/lib.new/ </dev/null
\$G 'Remove-Item -Recurse -Force C:\wvc\lib -ErrorAction SilentlyContinue; Rename-Item C:\wvc\lib.new lib; (Get-ChildItem C:\wvc\lib).Count'
EOF
}

cmd_stage_sample() {
  cmd_gradle -Pwebview.sample.windows :webview-sample:stageWindows
  local n want
  n="$(with_agent mirror_lib | tail -1 | tr -d '\r')"
  want="$(host "ls $SRC/webview-sample/build/windows/lib | wc -l")"
  [ "$n" = "$want" ] || die "C:/wvc/lib has $n files, the staged classpath $want"
  echo "winvm: C:/wvc/lib matches the staged classpath ($n files)"
}

# The scheduled task runs the job on tester's interactive desktop. conhost --headless keeps Windows Terminal from opening
# a window that would steal focus from the app under test. The task is this run's, and is removed after.
cmd_ui() {
  local file="$1" secs="${2:-300}"
  guest 'New-Item -ItemType Directory -Force C:\wvc\ui | Out-Null'
  gscp "$file" "$(vm):C:/wvc/ui/job-$RUN.ps1"
  run_ps <<PS
\$name = 'wvc-ui-$RUN'
\$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument '--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& C:\wvc\ui\job-$RUN.ps1 *> C:\wvc\ui\job-$RUN.out"'
\$principal = New-ScheduledTaskPrincipal -UserId 'tester' -LogonType Interactive -RunLevel Limited
\$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName \$name -Action \$action -Principal \$principal -Settings \$settings -Force | Out-Null
Start-ScheduledTask -TaskName \$name
Start-Sleep -Milliseconds 1500
\$start = Get-Date
while ((Get-ScheduledTask -TaskName \$name).State -eq 'Running' -and ((Get-Date) - \$start).TotalSeconds -lt $secs) { Start-Sleep -Milliseconds 500 }
if ((Get-ScheduledTask -TaskName \$name).State -eq 'Running') { Stop-ScheduledTask -TaskName \$name; 'winvm: the desktop job ran past $secs s and was stopped' }
Unregister-ScheduledTask -TaskName \$name -Confirm:\$false
Get-Content C:\wvc\ui\job-$RUN.out -ErrorAction SilentlyContinue
Remove-Item C:\wvc\ui\job-$RUN.ps1, C:\wvc\ui\job-$RUN.out -ErrorAction SilentlyContinue
PS
}

# Copies the result files the guest has and this box hasn't yet into apps/webview-sample/build/windows-results.
cmd_results() {
  local dest="$SAMPLE/build/windows-results" names missing=() n
  mkdir -p "$dest"
  names="$(guest 'Get-ChildItem -Name -Recurse -File C:\wvc\results' 2>/dev/null | tr -d '\r')"
  while IFS= read -r n; do
    [ -n "$n" ] && [ ! -e "$dest/${n//\\//}" ] && missing+=("$n")
  done <<<"$names"
  for n in "${missing[@]}"; do
    mkdir -p "$dest/$(dirname "${n//\\//}")"
    gscp "$(vm):C:/wvc/results/${n//\\//}" "$dest/${n//\\//}"
  done
  echo "winvm: ${#missing[@]} new result file(s) in $dest"
}

run_sample() {
  local mode="$1" only="$2" props="$3" job
  job="$(mktemp --suffix=.ps1)"
  cat > "$job" <<EOF
& C:\wvc\vm\run-sample.ps1 -Mode '$mode' -Run '$RUN' -Only '$only' -Props '$props' -TimeoutSec 2400
EOF
  guest 'New-Item -ItemType Directory -Force C:\wvc\vm | Out-Null'
  gscp "$HERE/vm/run-sample.ps1" "$(vm):C:/wvc/vm/run-sample.ps1"
  cmd_ui "$job" 2500
  rm -f "$job"
  cmd_results
}

cmd_checks() {
  run_sample checks "${1:-}" ""
  local log="$SAMPLE/build/windows-results/checks-$RUN.log"
  [ -f "$log" ] || die "no log for run $RUN: winvm.sh results, then look in $SAMPLE/build/windows-results"
  grep -E '^CHECK |^CHECKS ' "$log" || true
  grep -qE '^CHECKS DONE passed=[0-9]+ failed=0' "$log"
}

cmd_lab() { run_sample lab "" "${1:-}"; grep -hE '^LAB |^perf-lab: ' "$SAMPLE/build/windows-results/lab-$RUN.log" || true; }

cmd_wake() { host "$VIRSH send-key $DOMAIN KEY_RIGHTCTRL >/dev/null"; echo "winvm: sent Right Ctrl"; }

case "${1:-}" in
  state) cmd_state ;;
  up) cmd_up ;;
  adopt) cmd_adopt ;;
  down) cmd_down ;;
  setup) require_ours; cmd_setup ;;
  build) require_ours; cmd_build "${2:-x64}" ;;
  test) require_ours; cmd_test ;;
  gradle) shift; cmd_gradle "$@" ;;
  stage-sample) require_ours; cmd_stage_sample ;;
  checks) require_ours; cmd_checks "${2:-}" ;;
  lab) require_ours; cmd_lab "${2:-}" ;;
  results) require_ours; cmd_results ;;
  ps) require_ours; run_ps < "${2:?script}" ;;
  ui) require_ours; cmd_ui "${2:?script}" "${3:-300}" ;;
  wake) cmd_wake ;;
  *) sed -n '2,26p' "$0"; exit 2 ;;
esac
