#!/bin/sh
# Build an Apple-silicon tmux that only links Apple system dylibs. Homebrew's
# tmux links Homebrew libevent/ncurses paths and cannot be bundled as-is.
set -eu

OUTPUT="${1:?usage: build-portable-tmux.sh <output-path>}"
TMUX_VERSION="3.5a"
TMUX_SHA256="16216bd0877170dfcc64157085ba9013610b12b082548c7c9542cc0103198951"
LIBEVENT_VERSION="2.1.12-stable"
LIBEVENT_SHA256="92e6de1be9ec176428fd2367677e61ceffc2ee1cb119035037a27d346b0403bb"

if [ "$(uname -s)" != "Darwin" ] || [ "$(uname -m)" != "arm64" ]; then
  echo "build-portable-tmux.sh requires Apple-silicon macOS" >&2
  exit 2
fi

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/supermux-tmux.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT INT TERM
JOBS="$(sysctl -n hw.logicalcpu 2>/dev/null || echo 3)"

verify_sha256() {
  file="$1"
  expected="$2"
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [ "$actual" != "$expected" ]; then
    echo "sha256 mismatch for $file: expected $expected, got $actual" >&2
    exit 1
  fi
}

cd "$BUILD_DIR"
curl -fsSLo "libevent-${LIBEVENT_VERSION}.tar.gz" \
  "https://github.com/libevent/libevent/releases/download/release-${LIBEVENT_VERSION}/libevent-${LIBEVENT_VERSION}.tar.gz"
curl -fsSLo "tmux-${TMUX_VERSION}.tar.gz" \
  "https://github.com/tmux/tmux/releases/download/${TMUX_VERSION}/tmux-${TMUX_VERSION}.tar.gz"
verify_sha256 "libevent-${LIBEVENT_VERSION}.tar.gz" "$LIBEVENT_SHA256"
verify_sha256 "tmux-${TMUX_VERSION}.tar.gz" "$TMUX_SHA256"

tar xzf "libevent-${LIBEVENT_VERSION}.tar.gz"
tar xzf "tmux-${TMUX_VERSION}.tar.gz"

cd "$BUILD_DIR/libevent-${LIBEVENT_VERSION}"
./configure \
  --prefix="$BUILD_DIR/libevent-prefix" \
  --disable-shared \
  --enable-static \
  --disable-openssl >"$BUILD_DIR/libevent-configure.log" 2>&1 \
  || { cat "$BUILD_DIR/libevent-configure.log" >&2; exit 1; }
make -j"$JOBS" >"$BUILD_DIR/libevent-make.log" 2>&1 \
  || { cat "$BUILD_DIR/libevent-make.log" >&2; exit 1; }
make install >"$BUILD_DIR/libevent-install.log" 2>&1 \
  || { cat "$BUILD_DIR/libevent-install.log" >&2; exit 1; }

cd "$BUILD_DIR/tmux-${TMUX_VERSION}"
CPPFLAGS="-I$BUILD_DIR/libevent-prefix/include" \
  LDFLAGS="-L$BUILD_DIR/libevent-prefix/lib" \
  ./configure --disable-utf8proc >"$BUILD_DIR/tmux-configure.log" 2>&1 \
  || { cat "$BUILD_DIR/tmux-configure.log" >&2; exit 1; }
make -j"$JOBS" >"$BUILD_DIR/tmux-make.log" 2>&1 \
  || { cat "$BUILD_DIR/tmux-make.log" >&2; exit 1; }

mkdir -p "$(dirname "$OUTPUT")"
cp tmux "$OUTPUT"
strip -x "$OUTPUT"
chmod +x "$OUTPUT"

NON_SYSTEM_DYLIBS="$(otool -L "$OUTPUT" | tail -n +2 | awk '{print $1}' | grep -Ev '^(/usr/lib/|/System/)' || true)"
if [ -n "$NON_SYSTEM_DYLIBS" ]; then
  echo "portable tmux links non-system libraries:" >&2
  echo "$NON_SYSTEM_DYLIBS" >&2
  exit 1
fi

"$OUTPUT" -V
otool -L "$OUTPUT"
