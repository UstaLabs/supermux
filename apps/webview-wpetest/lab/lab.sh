#!/usr/bin/env bash
# Drives the WPE lab for apps/webview-wpetest (docs/superpowers/plans/2026-10-30-webview-linux-wpe.md) from this box.
# Every command is safe to repeat. The host is `desktop` while it answers, else this box; LAB_HOST=box|desktop forces it.
#
#   lab.sh where                  print the host the next command uses
#   lab.sh image                  copy lab/ there and build the image webview-wpelab:2.54.2 (apt only, no compile)
#   lab.sh stage                  build the jars on the Mac (:webview-wpetest:stageLab) and copy them there (lib/)
#   lab.sh native                 copy native/ there and build native/out/libwvlab.so inside the image
#   lab.sh run SCENARIO [PROPS]   run the test app with a scenario; results to apps/webview-wpetest/build/lab-results/<host>/
#   lab.sh size                   Z1: the bundle size of the image's WPE (size.sh)
#   lab.sh probe                  print the image's WPE, JDK and EGL/GLES header versions
#   lab.sh clean                  remove the host's work folder and the image
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(dirname "$HERE")"
APPS="$(dirname "$MODULE")"
IMAGE=webview-wpelab:2.54.2
MAIN=/home/ahmet/.mux/worktrees/supermux-3962b5bf/c58b82b4-abaf-40d9-a195-6376a386ddd1
SSHO=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=15)

die() { echo "lab: $*" >&2; exit 1; }

pick_host() {
  case "${LAB_HOST:-}" in
    box|desktop) echo "$LAB_HOST"; return ;;
    "") ;;
    *) die "LAB_HOST must be box or desktop" ;;
  esac
  if timeout 20 ssh "${SSHO[@]}" desktop true 2>/dev/null; then echo desktop; else echo box; fi
}
HOST="$(pick_host)"

# The work folder: on desktop relative to its home (rsync and ssh both resolve it there), here under ~/.cache.
if [ "$HOST" = desktop ]; then WORK=work/webview-wpelab; else WORK="$HOME/.cache/webview-wpelab"; fi
on() { if [ "$HOST" = desktop ]; then ssh "${SSHO[@]}" desktop "$1"; else bash -c "$1"; fi; }
dest() { if [ "$HOST" = desktop ]; then echo "desktop:$WORK/$1"; else echo "$WORK/$1"; fi; }
push() { on "mkdir -p $WORK/$2"; rsync -a --delete "$1" "$(dest "$2")/"; }
docker_limits() {
  if [ "$HOST" = desktop ]; then echo "--memory=4g --memory-swap=4g --cpus=4"; else echo "--memory=3g --memory-swap=3g --cpus=2 --cpu-shares=256"; fi
}
# docker run with the work folder at /lab, as the host user, with a writable HOME inside it.
drun() {
  on "mkdir -p $WORK/results $WORK/home && cd $WORK && docker run --rm --init --network=none --shm-size=1g $(docker_limits) \
    --user \$(id -u):\$(id -g) -e HOME=/lab/home -e LAB_HOST_NAME=$HOST -v \$PWD:/lab $IMAGE $1"
}
pull_results() {
  mkdir -p "$MODULE/build/lab-results/$HOST"
  rsync -a "$(dest results)/" "$MODULE/build/lab-results/$HOST/"
  echo "lab: results in $MODULE/build/lab-results/$HOST"
}

cmd_image() {
  push "$HERE/" lab
  local nice=""; [ "$HOST" = box ] && nice="nice -n 10"
  on "cd $WORK/lab && $nice docker build -q -t $IMAGE --build-arg SNAPSHOT=20261029T000000Z . >/dev/null && docker image inspect -f '{{.Size}}' $IMAGE"
  echo "lab: image $IMAGE built on $HOST"
}

cmd_stage() {
  (cd "$APPS" && webview-mac-gradle :webview-wpetest:stageLab)
  local root macdir
  root="$(git -C "$APPS" rev-parse --show-toplevel)"
  macdir=work/webview-compose
  [ "$root" = "$MAIN" ] || macdir="work/webview-compose-$(printf %s "$root" | sha1sum | cut -c1-8)"
  rm -rf "$MODULE/build/lab/lib"
  mkdir -p "$MODULE/build/lab"
  scp -q -r "mac:$macdir/apps/webview-wpetest/build/lab/lib" "$MODULE/build/lab/"
  push "$MODULE/build/lab/lib/" lib
  echo "lab: $(ls "$MODULE/build/lab/lib" | wc -l) jars staged on $HOST"
}

cmd_native() {
  push "$MODULE/native/" native
  drun "bash /lab/native/build.sh"
}

cmd_run() {
  local scenario="${1:?scenario}"; shift
  on "test -f $WORK/native/out/libwvlab.so && test -d $WORK/lib && docker image inspect $IMAGE >/dev/null" \
    || die "the lab on $HOST is not ready: run lab.sh image, stage and native first"
  push "$HERE/" lab
  local code=0
  drun "bash /lab/lab/in-container.sh $scenario $*" || code=$?
  pull_results
  echo "lab: $scenario on $HOST exited $code"
  return "$code"
}

cmd_size() {
  push "$HERE/" lab
  drun "bash /lab/lab/size.sh"
  pull_results
}

cmd_probe() {
  drun "sh -c 'pkg-config --modversion wpe-webkit-2.0 wpe-platform-2.0 wpe-platform-headless-2.0; java -version 2>&1 | head -1; ls /usr/include/EGL/eglext.h /usr/include/GLES2/gl2ext.h'"
}

cmd_clean() {
  on "rm -rf $WORK; docker image rm -f $IMAGE >/dev/null 2>&1 || true"
  echo "lab: cleaned $HOST"
}

case "${1:-}" in
  where) echo "$HOST" ;;
  image) cmd_image ;;
  stage) cmd_stage ;;
  native) cmd_native ;;
  run) shift; cmd_run "$@" ;;
  size) cmd_size ;;
  probe) cmd_probe ;;
  clean) cmd_clean ;;
  *) sed -n '2,14p' "$0"; exit 2 ;;
esac
