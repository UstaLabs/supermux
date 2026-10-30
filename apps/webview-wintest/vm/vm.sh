#!/usr/bin/env bash
# Drives the Windows 11 x64 VM `supermux-windows` (libvirt on `desktop`) for the WebView2 capture + focus test
# (apps/webview-wintest). Runs on the Linux box. Every command is safe to repeat.
#
#   vm.sh up                    start the VM if it is shut off; wait for SSH and tester's console session
#   vm.sh state                 the domain's state; the guest's uptime and free disk when it runs
#   vm.sh reboot                restart the guest and wait for it
#   vm.sh ps FILE.ps1           run a script in the SSH session (elevated, session 0: no desktop)
#   vm.sh ui FILE.ps1 [SECS]    run a script on tester's desktop through a scheduled task; print its output
#   vm.sh push LOCAL REMOTE     copy to the guest over the jump host, small files only (REMOTE like C:/wv2test/x)
#   vm.sh pull REMOTE LOCAL     copy from the guest
#   vm.sh build [CL_FLAG]       copy native/ to C:/wv2test/native and build wv2capture.dll there
#   vm.sh gradle TASK...        run Gradle on `desktop` (Docker, JDK 17) on a copy of this worktree's apps/
#   vm.sh stage                 build the jars (:webview-wintest:stageVm) and mirror them from `desktop` to C:/wv2test/lib
#   vm.sh app SCENARIO [PROPS]  run the test app on the desktop with a scenario; new results land in build/vm-results
#   vm.sh wake                  wake the guest's display (Right Ctrl; Shift five times opens Sticky Keys)
#   vm.sh down                  shut the guest down and wait until libvirt reports it shut off
#
# `up` refuses to touch a VM it finds running unless this helper started it (a marker on `desktop`): another session
# may be using it. Never point this at supermux-win-fresh or supermux-linux.
set -euo pipefail

JUMP=ahmet@100.104.43.81
DOMAIN=supermux-windows
VM=tester@192.168.122.242
VIRSH="virsh -c qemu:///system"
WORK=work/wv2test                    # on `desktop`, relative to ahmet's home
MARK="$WORK/vm-started-by-wv2test"   # present while this helper owns the running VM
IMAGE=supermux-build                 # eclipse-temurin:21 + openjdk-17 (desktop:~/work/Dockerfile.supermux-build)
HSSHO=(-o BatchMode=yes -o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=15)
# The guest's own options. ssh keeps the first value of a repeated option, so these can't extend HSSHO.
GSSHO=(-o BatchMode=yes -o LogLevel=ERROR -o ConnectTimeout=40 -o ServerAliveInterval=15
  -o StrictHostKeyChecking=accept-new -J "$JUMP")
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(dirname "$HERE")"
APPS="$(dirname "$MODULE")"

die() { echo "vm: $*" >&2; exit 1; }
host() { ssh "${HSSHO[@]}" "$JUMP" "$@"; }

# Retries a guest ssh/scp whose connection was never set up: a busy guest's sshd can be slow to answer (Defender
# scanning, updates). Nothing ran on the guest then, so the retry is safe. Any other failure is returned as it is.
retry_conn() {
  local attempt rc err
  err="$(mktemp)"
  for attempt in 1 2 3 4; do
    rc=0
    "$@" 2>"$err" || rc=$?
    if [ "$rc" -eq 255 ] && grep -qiE 'banner exchange|kex_exchange_identification|Connection timed out|Connection refused|Connection closed by [0-9]' "$err"; then
      sleep $((attempt * 5))
      continue
    fi
    break
  done
  cat "$err" >&2
  rm -f "$err"
  return "$rc"
}
guest() { retry_conn ssh "${GSSHO[@]}" "$VM" "$@"; }
gscp() { retry_conn scp -q "${GSSHO[@]}" "$@"; }
domstate() { host "$VIRSH domstate $DOMAIN" 2>/dev/null | head -1 | tr -d '\r'; }
ssh_ok() { timeout 50 ssh "${GSSHO[@]}" "$VM" hostname >/dev/null 2>&1; }
wait_ssh() { local i; for i in $(seq 1 "$1"); do ssh_ok && return 0; sleep 5; done; return 1; }

# Runs "$@" with an ssh-agent holding this box's key, so `ssh -A` lets `desktop` reach the guest for bulk copies
# (desktop -> guest is local and fast; this box -> guest over the jump host is about 1.5 MB/s).
with_agent() {
  if ssh-add -l >/dev/null 2>&1; then "$@"; return; fi
  local sock rc=0
  sock="$(mktemp -u "${TMPDIR:-/tmp}/wv2-agent.XXXXXX")"
  eval "$(ssh-agent -s -a "$sock")" >/dev/null
  ssh-add -q "$HOME/.ssh/id_ed25519"
  "$@" || rc=$?
  ssh-agent -k >/dev/null 2>&1 || true
  return "$rc"
}

# Runs the PowerShell script on stdin in the SSH session and prints its output.
run_ps() {
  local tmp name; tmp="$(mktemp --suffix=.ps1)"; cat > "$tmp"
  name="ssh-job-$$-$RANDOM.ps1"
  guest 'New-Item -ItemType Directory -Force C:\wv2test\ui | Out-Null'
  gscp "$tmp" "$VM:C:/wv2test/ui/$name"
  rm -f "$tmp"
  local rc=0
  guest "powershell -NoProfile -ExecutionPolicy Bypass -File C:\\wv2test\\ui\\$name; Remove-Item C:\\wv2test\\ui\\$name" || rc=$?
  return "$rc"
}

cmd_state() {
  local state; state="$(domstate)"
  echo "vm: $DOMAIN is ${state:-unknown}; marker $(host "test -f $MARK && cat $MARK || echo absent")"
  [ "$state" = running ] && ssh_ok || return 0
  run_ps <<'PS'
$boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
"vm: up since $($boot.ToString('yyyy-MM-dd HH:mm')) guest time; C: free {0:N1} GB" -f ((Get-Volume -DriveLetter C).SizeRemaining / 1GB)
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
  local state; state="$(domstate)"
  case "$state" in
    running)
      host "test -f $MARK" || die "$DOMAIN is already running and this helper did not start it: another session may be using it"
      ;;
    "shut off")
      echo "vm: shut off; starting $DOMAIN on desktop"
      host "mkdir -p $WORK && $VIRSH start $DOMAIN >/dev/null && date -u +%FT%TZ > $MARK"
      ;;
    *) die "$DOMAIN is '${state:-unknown}'; look at it (virsh on desktop) before starting it" ;;
  esac
  wait_ssh 72 || die "no SSH after 6 min; look at the console (vnc://100.104.43.81:5902)"
  wait_console || die "SSH works but nobody is signed in on the console; the UI steps need tester's desktop"
  cmd_state
}

cmd_reboot() {
  guest 'shutdown /r /t 0' >/dev/null 2>&1 || true
  sleep 40
  wait_ssh 72 || die "no SSH after the reboot"
  wait_console || die "no console session after the reboot"
  cmd_state
}

# The scheduled task runs the job on tester's interactive desktop. conhost --headless keeps Windows Terminal from
# opening a console window that would steal focus from the app under test.
cmd_ui() {
  local file="$1" secs="${2:-300}"
  guest 'New-Item -ItemType Directory -Force C:\wv2test\ui | Out-Null'
  gscp "$file" "$VM:C:/wv2test/ui/job.ps1"
  run_ps <<PS
\$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument '--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& C:\wv2test\ui\job.ps1 *> C:\wv2test\ui\job.out"'
\$principal = New-ScheduledTaskPrincipal -UserId 'tester' -LogonType Interactive -RunLevel Limited
\$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName wv2ui -Action \$action -Principal \$principal -Settings \$settings -Force | Out-Null
Remove-Item C:\wv2test\ui\job.out -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName wv2ui
Start-Sleep -Milliseconds 1500
\$start = Get-Date
while ((Get-ScheduledTask -TaskName wv2ui).State -eq 'Running' -and ((Get-Date) - \$start).TotalSeconds -lt $secs) { Start-Sleep -Milliseconds 500 }
if ((Get-ScheduledTask -TaskName wv2ui).State -eq 'Running') { Stop-ScheduledTask -TaskName wv2ui; 'vm: the desktop job ran past $secs s and was stopped' }
Get-Content C:\wv2test\ui\job.out -ErrorAction SilentlyContinue
PS
}

cmd_push() { gscp -r "$1" "$VM:$2"; }
cmd_pull() { mkdir -p "$2"; gscp -r "$VM:$1" "$2"; }

cmd_build() {
  run_ps >/dev/null <<'PS'
New-Item -ItemType Directory -Force C:\wv2test\native\out | Out-Null
Get-ChildItem C:\wv2test\native -File | Remove-Item -Force
PS
  gscp "$MODULE"/native/*.h "$MODULE"/native/*.cpp "$MODULE"/native/build.cmd "$VM:C:/wv2test/native/"
  guest "cmd /c C:\\wv2test\\native\\build.cmd ${1:-}"
}

# Gradle never runs on this box (its load is often above 40) nor on the Mac (overloaded). It runs on `desktop` in the
# supermux-build image with JDK 17, in a Gradle home of its own, capped: `desktop` has 15 GB and the VM holds 8 of them.
cmd_gradle() {
  [ "$#" -gt 0 ] || die "gradle needs at least one task"
  host "mkdir -p $WORK/src/apps $WORK/gradle-home $WORK/home"
  rsync -az --delete -e "ssh ${HSSHO[*]}" \
    --exclude build --exclude .gradle --exclude .kotlin --exclude node_modules --exclude local.properties \
    --exclude kotlin-js-store --exclude 'desktop/resources/*/*' \
    "$APPS/" "$JUMP:$WORK/src/apps/"
  host "cat > $WORK/gradle-home/gradle.properties" <<'PROPS'
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m
kotlin.daemon.jvmargs=-Xmx1g
kotlin.compiler.execution.strategy=in-process
org.gradle.workers.max=3
org.gradle.daemon=false
PROPS
  local free; free="$(host "awk '/MemAvailable/ {print int(\$2/1024)}' /proc/meminfo")"
  [ "${free:-0}" -ge 3500 ] || die "desktop has only ${free} MB available; Gradle needs 3 GB (is something else running?)"
  host "cd $WORK && docker run --rm --init --memory=3g --memory-swap=3g --cpus=3 \
    --user \$(id -u):\$(id -g) -e HOME=/w/home -e GRADLE_USER_HOME=/w/gradle-home \
    -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 -e JAVA_TOOL_OPTIONS=-Duser.home=/w/home -v \$PWD:/w -w /w/src/apps $IMAGE \
    ./gradlew --no-daemon --console=plain $*"
}

# Mirrors desktop's build/vm/lib into C:\wv2test\lib (copied to lib.new, then swapped in, so a cut copy never leaves a
# half classpath behind). Runs on `desktop` with this box's agent forwarded.
mirror_lib() {
  ssh -A "${HSSHO[@]}" "$JUMP" bash -s <<EOF
set -euo pipefail
O="-o BatchMode=yes -o LogLevel=ERROR -o StrictHostKeyChecking=accept-new"
G="ssh \$O $VM"
\$G 'Remove-Item -Recurse -Force C:\wv2test\lib.new -ErrorAction SilentlyContinue; New-Item -ItemType Directory -Force C:\wv2test\lib.new | Out-Null'
scp -q \$O $WORK/src/apps/webview-wintest/build/vm/lib/* $VM:C:/wv2test/lib.new/
\$G 'Remove-Item -Recurse -Force C:\wv2test\lib -ErrorAction SilentlyContinue; Rename-Item C:\wv2test\lib.new lib; (Get-ChildItem C:\wv2test\lib).Count'
EOF
}

cmd_stage() {
  cmd_gradle :webview-wintest:stageVm
  local n; n="$(with_agent mirror_lib | tail -1 | tr -d '\r')"
  local want; want="$(host "ls $WORK/src/apps/webview-wintest/build/vm/lib | wc -l")"
  [ "$n" = "$want" ] || die "C:/wv2test/lib has $n files, the staged classpath $want"
  echo "vm: C:/wv2test/lib matches the staged classpath ($n files)"
}

# Copies the result files the guest has and this box hasn't yet into build/vm-results.
pull_results() {
  local dest="$MODULE/build/vm-results" names missing=()
  mkdir -p "$dest"
  names="$(guest 'Get-ChildItem -Name C:\wv2test\results' 2>/dev/null | tr -d '\r')"
  local n
  while IFS= read -r n; do
    [ -n "$n" ] && [ ! -e "$dest/$n" ] && missing+=("$VM:C:/wv2test/results/$n")
  done <<<"$names"
  [ "${#missing[@]}" -eq 0 ] || gscp "${missing[@]}" "$dest/"
  echo "vm: ${#missing[@]} new result file(s) in $dest"
}

cmd_app() {
  local scenario="$1"; shift
  local props="$*" job
  job="$(mktemp --suffix=.ps1)"
  cat > "$job" <<EOF
& C:\wv2test\vm\run-app.ps1 -Scenario '$scenario' -Props '$props' -TimeoutSec 900
EOF
  guest 'New-Item -ItemType Directory -Force C:\wv2test\vm | Out-Null'
  gscp "$HERE/run-app.ps1" "$VM:C:/wv2test/vm/run-app.ps1"
  cmd_ui "$job" 960
  rm -f "$job"
  pull_results
}

cmd_wake() { host "$VIRSH send-key $DOMAIN KEY_RIGHTCTRL >/dev/null"; echo "vm: sent Right Ctrl"; }

cmd_down() {
  local state; state="$(domstate)"
  if [ "$state" = "shut off" ]; then host "rm -f $MARK"; echo "vm: already shut off"; return 0; fi
  host "test -f $MARK" || die "$DOMAIN is running and this helper did not start it: leaving it alone"
  guest 'shutdown /s /t 5' >/dev/null 2>&1 || host "$VIRSH shutdown $DOMAIN >/dev/null" || true
  local i
  for i in $(seq 1 48); do
    if [ "$(domstate)" = "shut off" ]; then host "rm -f $MARK"; echo "vm: shut off"; return 0; fi
    sleep 5
  done
  echo "vm: still '$(domstate)' after 4 min; forcing it off (virsh destroy)"
  host "$VIRSH destroy $DOMAIN >/dev/null"
  host "rm -f $MARK"
  echo "vm: destroyed"
}

case "${1:-}" in
  up) cmd_up ;;
  state) cmd_state ;;
  reboot) cmd_reboot ;;
  ps) run_ps < "${2:?script}" ;;
  ui) cmd_ui "${2:?script}" "${3:-300}" ;;
  push) cmd_push "${2:?local}" "${3:?remote}" ;;
  pull) cmd_pull "${2:?remote}" "${3:?local}" ;;
  build) cmd_build "${2:-}" ;;
  gradle) shift; cmd_gradle "$@" ;;
  stage) cmd_stage ;;
  app) shift; cmd_app "$@" ;;
  wake) cmd_wake ;;
  down) cmd_down ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
