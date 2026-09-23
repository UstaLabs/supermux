#!/usr/bin/env bash
# scripts/check-zmx-bundle.sh — is THIS directory a zmx bundle we are willing to ship?
#
#   scripts/check-zmx-bundle.sh <dir> [expected-target]
#
# A "bundle" is what scripts/build-zmx.sh writes:
#
#   <dir>/bin/zmx              the patched daemon
#   <dir>/bin/mux-zmx-helper   the framed broker helper
#   <dir>/manifest.json        what they were built from, and what they hash to
#
# The broker already refuses to exec binaries that disagree with the manifest
# beside them (src/core/terminal/zmx/helper.ts, verifyHelperManifest). That check
# is self-consistent by construction: rebuild anything and the manifest is
# rewritten to match, so it proves the pair was not tampered with AFTER the build
# and nothing else. What it cannot see is the question a release has to answer —
# is this the zmx we PINNED?
#
# So this script re-asks it against vendor/zmx/upstream.lock.json:
#
#   * the manifest's zmx commit is the lockfile's commit,
#   * the manifest's patch sha256 is the lockfile's patch sha256,
#   * the helper's ABI is the one the helper sources declare (and therefore the
#     one the TypeScript speaks),
#   * the binaries on disk hash to what the manifest says,
#   * and, when an expected target is given, the bundle was built FOR that target.
#
# Anything staged into a release artifact — the compiled broker's embedded copy,
# the desktop app image, the Docker image — goes through here first, which is
# what makes "the lock/patch hash is in the release manifest" a checked fact
# rather than a comment.
set -euo pipefail

DIR="${1:?usage: check-zmx-bundle.sh <dir> [expected-target]}"
EXPECTED_TARGET="${2:-}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$REPO_ROOT/vendor/zmx/upstream.lock.json"
PROTOCOL="$REPO_ROOT/src/core/terminal/zmx/helper/protocol.zig"

die() { printf '[zmx-bundle] ERROR: %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 is required"
[[ -f "$LOCK" ]] || die "missing $LOCK"
[[ -d "$DIR" ]] || die "not a directory: $DIR"

MANIFEST="$DIR/manifest.json"
ZMX_BIN="$DIR/bin/zmx"
HELPER_BIN="$DIR/bin/mux-zmx-helper"
for f in "$MANIFEST" "$ZMX_BIN" "$HELPER_BIN"; do
  [[ -f "$f" ]] || die "incomplete bundle: missing $f (run scripts/build-zmx.sh)"
done

sha256_of() { ( sha256sum "$1" 2>/dev/null || shasum -a 256 "$1" ) | cut -d' ' -f1; }

# The ABI the helper SOURCES declare. Taken from the source, not from running the
# binary: a cross-compiled bundle is exactly the one we cannot execute here, and
# it is also exactly the one nobody has ever run.
ABI_WANT="$(sed -n 's/^pub const ABI_VERSION: u32 = \([0-9]*\);.*/\1/p' "$PROTOCOL")"
[[ -n "$ABI_WANT" ]] || die "could not read ABI_VERSION from $PROTOCOL"

python3 - "$LOCK" "$MANIFEST" "$(sha256_of "$ZMX_BIN")" "$(sha256_of "$HELPER_BIN")" \
         "$ABI_WANT" "$EXPECTED_TARGET" "$DIR" <<'PY'
import json, sys

lock_path, manifest_path, zmx_sha, helper_sha, abi_want, expected_target, bundle_dir = sys.argv[1:8]
lock = json.load(open(lock_path))
try:
    m = json.load(open(manifest_path))
except Exception as e:
    raise SystemExit("[zmx-bundle] ERROR: %s is not readable JSON: %s" % (manifest_path, e))

problems = []
def want(label, got, expected):
    if got != expected:
        problems.append("%s: bundle says %r, expected %r" % (label, got, expected))

want("manifest schema", m.get("schema"), 1)
want("helper ABI", str(m.get("abi")), str(abi_want))
want("zmx commit", m.get("zmx", {}).get("commit"), lock["zmx"]["commit"])
want("patch sha256", m.get("patch", {}).get("sha256"), lock["patches"][0]["sha256"])
want("zmx binary sha256", zmx_sha, m.get("zmx", {}).get("sha256"))
want("helper binary sha256", helper_sha, m.get("helper", {}).get("sha256"))

# A Debug zmx runs the pty at ~43 KiB/s and is indistinguishable from a release
# one by sha alone (scripts/build-zmx.sh says so at length). It is not shippable.
if m.get("optimize") not in ("ReleaseSafe", "ReleaseFast", "ReleaseSmall"):
    problems.append("optimize: bundle was built %r, which is not a release mode" % (m.get("optimize"),))

# `native` is what a build on the machine it will run on records; a cross build
# names the target explicitly. Both are acceptable for that target, nothing else is.
if expected_target and m.get("target") not in (expected_target, "native"):
    problems.append("target: bundle is for %r, staging %r" % (m.get("target"), expected_target))

if problems:
    print("[zmx-bundle] %s is NOT shippable:" % bundle_dir, file=sys.stderr)
    for p in problems:
        print("  * " + p, file=sys.stderr)
    raise SystemExit(1)

print("[zmx-bundle] OK %s: zmx %s (patch %s…), helper ABI %s, %s/%s" % (
    bundle_dir, lock["zmx"]["commit"][:12], lock["patches"][0]["sha256"][:12],
    m["abi"], m.get("target"), m.get("optimize")))
PY
