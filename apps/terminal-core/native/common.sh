# Shared helpers for native/build.sh and wasm/build.sh. Source after setting
# TARGET (used in log lines). Resolves everything from native/upstream.lock.json.
# shellcheck shell=bash

COMMON_NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(cd "$COMMON_NATIVE_DIR/.." && pwd)"
LOCK="$COMMON_NATIVE_DIR/upstream.lock.json"
BUILD_DIR="$PKG_DIR/build"
UPSTREAM_DIR="$BUILD_DIR/upstream/ghostty"
ZIG_JOBS="${ST_ZIG_JOBS:-2}"
ZIG_GLOBAL_CACHE="$BUILD_DIR/zig-global-cache"

log() { printf '[terminal-core %s] %s\n' "$TARGET" "$*" >&2; }
die() { printf '[terminal-core %s] ERROR: %s\n' "$TARGET" "$*" >&2; exit 1; }

lock() { python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."): d=d[k]
print(d)' "$LOCK" "$1"; }

lock_opt() { python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
try:
  for k in sys.argv[2].split("."): d=d[k]
  print(d)
except KeyError: print("")' "$LOCK" "$1"; }

command -v python3 >/dev/null || die "python3 is required"
command -v git >/dev/null || die "git is required"

GHOSTTY_REPO="$(lock ghostty.repository)"
GHOSTTY_SHA="$(lock ghostty.commit)"
ZIG_VERSION="$(lock zig.version)"
LIBVT_VERSION="$(lock ghostty.libghostty_vt_version)"

# ---------------------------------------------------------------- host ----
host_os="$(uname -s)"; host_arch="$(uname -m)"
case "$host_os" in
  Linux) host_os=linux ;; Darwin) host_os=macos ;;
  MINGW*|MSYS*|CYGWIN*) host_os=windows ;;
  *) die "unsupported host OS $host_os" ;;
esac
case "$host_arch" in
  x86_64|amd64) host_arch=x86_64 ;; aarch64|arm64) host_arch=aarch64 ;;
  *) die "unsupported host arch $host_arch" ;;
esac
HOST_KEY="$host_arch-$host_os"

# --------------------------------------------------------------- zig ------
ensure_zig() {
  local home="${ST_ZIG_HOME:-$HOME/.local/zig/$ZIG_VERSION}"
  ZIG="$home/zig"
  if [[ -x "$ZIG" && "$("$ZIG" version)" == "$ZIG_VERSION" ]]; then
    return
  fi
  local url sha
  url="$(lock_opt "zig.hosts.$HOST_KEY.url")"
  sha="$(lock_opt "zig.hosts.$HOST_KEY.sha256")"
  [[ -n "$url" && -n "$sha" ]] || die "no pinned Zig $ZIG_VERSION tarball for host $HOST_KEY in upstream.lock.json"
  log "downloading Zig $ZIG_VERSION for $HOST_KEY"
  local dl="$home.download"
  mkdir -p "$dl"
  curl -sfL --retry 3 -o "$dl/zig.tar.xz" "$url"
  local got
  got="$( (sha256sum "$dl/zig.tar.xz" 2>/dev/null || shasum -a 256 "$dl/zig.tar.xz") | cut -d' ' -f1)"
  [[ "$got" == "$sha" ]] || die "Zig tarball sha256 mismatch: got $got want $sha"
  verify_zig_minisign "$dl/zig.tar.xz" "$url.minisig"
  mkdir -p "$home"
  tar -xJf "$dl/zig.tar.xz" -C "$home" --strip-components=1
  rm -rf "$dl"
  [[ "$("$ZIG" version)" == "$ZIG_VERSION" ]] || die "installed Zig reports wrong version"
}

# Verify the tarball's minisign signature against the pinned Zig public key.
# The sha256 pin above is always enforced; the signature check additionally
# needs python3 + cryptography and is skipped (with a warning) without them.
verify_zig_minisign() {
  local file="$1" sig_url="$2" pubkey
  pubkey="$(lock zig.minisign_public_key)"
  curl -sfL --retry 3 -o "$file.minisig" "$sig_url" || die "cannot fetch $sig_url"
  python3 - "$file" "$file.minisig" "$pubkey" <<'PY' || die "Zig minisign verification failed"
import base64, hashlib, sys
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
except ImportError:
    print("warning: python cryptography missing; minisign check skipped (sha256 enforced)", file=sys.stderr)
    sys.exit(0)
path, sig_path, pk_b64 = sys.argv[1:4]
pk = base64.b64decode(pk_b64)
lines = open(sig_path).read().splitlines()
sig = base64.b64decode(lines[1])
trusted = lines[2][len("trusted comment: "):].encode()
global_sig = base64.b64decode(lines[3])
assert pk[:2] == b"Ed" and sig[:2] == b"ED" and sig[2:10] == pk[2:10], "key id mismatch"
key = Ed25519PublicKey.from_public_bytes(pk[10:])
key.verify(sig[10:], hashlib.blake2b(open(path, "rb").read(), digest_size=64).digest())
key.verify(global_sig, sig[10:] + trusted)
print("minisign signature OK", file=sys.stderr)
PY
}

# ---------------------------------------------------------- upstream -----
ensure_upstream() {
  if [[ -d "$UPSTREAM_DIR/.git" ]] &&
     [[ "$(git -C "$UPSTREAM_DIR" rev-parse HEAD 2>/dev/null)" == "$GHOSTTY_SHA" ]]; then
    if [[ -n "$(git -C "$UPSTREAM_DIR" status --porcelain --untracked-files=no)" ]]; then
      die "upstream cache $UPSTREAM_DIR has local modifications; delete it to refetch"
    fi
    return
  fi
  log "fetching Ghostty $GHOSTTY_SHA"
  rm -rf "$UPSTREAM_DIR"
  mkdir -p "$UPSTREAM_DIR"
  git -C "$UPSTREAM_DIR" init -q
  git -C "$UPSTREAM_DIR" remote add origin "$GHOSTTY_REPO"
  git -C "$UPSTREAM_DIR" fetch -q --depth 1 origin "$GHOSTTY_SHA"
  git -C "$UPSTREAM_DIR" checkout -q --detach FETCH_HEAD
  [[ "$(git -C "$UPSTREAM_DIR" rev-parse HEAD)" == "$GHOSTTY_SHA" ]] || die "fetched commit mismatch"
}

