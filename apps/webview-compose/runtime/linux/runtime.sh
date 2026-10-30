#!/usr/bin/env bash
# Builds and checks webview-compose's WPE runtime on `desktop` (the heavy-build machine; never on this box).
#   runtime.sh image        copy runtime/linux/ and native/linux/ to desktop:~/work/wpe-runtime and build both images
#   runtime.sh build [glue] start build-runtime.sh in a detached, capped container: all (WebKit, its libavif with the
#                           dav1d decoder only, the glue), or the glue only
#   runtime.sh status       the build container's state and the last lines of its log
#   runtime.sh check        the runtime's libraries all resolve in the plain Ubuntu 24.04 image (no WPE of its own), and
#                           which AV1 libraries it has (libavif and dav1d; no encoder)
#   runtime.sh fetch        copy the runtime's .tar.zst, .tar.gz and manifest to apps/webview-compose/build/wpe-runtime/
#   runtime.sh clean        remove ~/work/wpe-runtime and both images from desktop
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="$(dirname "$(dirname "$HERE")")"
NAME=wpe-runtime-2.54.2-linux-x64
SSHO=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=15)
die() { echo "runtime: $*" >&2; exit 1; }
d() { ssh "${SSHO[@]}" desktop "$1"; }
timeout 20 ssh "${SSHO[@]}" desktop true 2>/dev/null || die "desktop doesn't answer: the runtime is only built there"

case "${1:-}" in
  image)
    d "mkdir -p work/wpe-runtime/runtime work/wpe-runtime/glue"
    rsync -a --delete "$HERE/" desktop:work/wpe-runtime/runtime/
    rsync -a --delete "$MODULE/native/linux/" desktop:work/wpe-runtime/glue/
    d "cd work/wpe-runtime/runtime && docker build -q -t webview-wpe-build:24.04 -f Dockerfile . >/dev/null && docker build -q -t webview-wpe-run:24.04 -f run.Dockerfile . >/dev/null && docker image ls --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep webview-wpe"
    ;;
  build)
    mode="${2:-all}"
    rsync -a --delete "$HERE/" desktop:work/wpe-runtime/runtime/
    rsync -a --delete "$MODULE/native/linux/" desktop:work/wpe-runtime/glue/
    d "docker rm -f wpe-runtime-build >/dev/null 2>&1; cd work/wpe-runtime && docker run -d --name wpe-runtime-build \
      --cpus=4 --memory=11g --memory-swap=11g -e JOBS=4 --user \$(id -u):\$(id -g) -e HOME=/work \
      -v \$PWD:/work webview-wpe-build:24.04 bash /work/runtime/build-runtime.sh $mode >/dev/null && echo started"
    ;;
  status)
    d "docker ps -a --filter name=wpe-runtime-build --format '{{.Status}}'; docker logs --tail 4 wpe-runtime-build 2>&1 | tail -4"
    ;;
  check)
    d "cd work/wpe-runtime/out && test -d $NAME && docker run --rm -v \$PWD/$NAME:/opt/wpe-runtime:ro webview-wpe-run:24.04 \
      sh -c 'ldd /opt/wpe-runtime/lib/libwebview-wpe.so /opt/wpe-runtime/lib/libWPEWebKit-2.0.so.1 /opt/wpe-runtime/libexec/* | grep -c \"not found\"; grep -E \"^(version|abi|arch|glibc)=\" /opt/wpe-runtime/manifest.properties; ldd --version | head -1; echo AV1: \$(ls /opt/wpe-runtime/lib | grep -E \"^lib(avif|dav1d|aom|rav1e|SvtAv1|gav1|yuv)\")'" || true
    ;;
  fetch)
    mkdir -p "$MODULE/build/wpe-runtime"
    scp -q "desktop:work/wpe-runtime/out/$NAME.tar.zst" "desktop:work/wpe-runtime/out/$NAME.tar.gz" "desktop:work/wpe-runtime/out/$NAME/manifest.properties" "$MODULE/build/wpe-runtime/"
    ls -l "$MODULE/build/wpe-runtime"
    ;;
  clean)
    d "docker rm -f wpe-runtime-build >/dev/null 2>&1; docker image rm -f webview-wpe-build:24.04 webview-wpe-run:24.04 >/dev/null 2>&1; rm -rf work/wpe-runtime; echo cleaned"
    ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
