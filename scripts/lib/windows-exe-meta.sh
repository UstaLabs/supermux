# Sourced by build-binary.sh and build-sessiond.sh (POSIX sh).
#
# Windows shows an exe's version-info resource in Task Manager, "Programs and Features" and the
# Windows Firewall "allow this app" prompt. A plain `bun build --compile` exe says "Bun" / "Oven";
# these stamp supermux's own strings and icon. Bun only applies its --windows-* flags when it runs
# ON Windows (from Linux it refuses: "only available when compiling on Windows"), which is where
# the release builds the Windows exes (release.yml build-desktop-windows, Git Bash). A cross-build
# keeps Bun's metadata and says so.

# "1.99.0-test.18" -> "1.99.0.18", "1.5.0" -> "1.5.0.0", anything else -> "0.0.0.0".
windows_exe_version() {
  echo "$1" | sed -n -E 's/^([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z-]+\.([0-9]+))?.*$/\1.\2.\3.\5/p' |
    sed -E 's/\.$/.0/' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' || echo "0.0.0.0"
}

on_windows_host() {
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*|Windows_NT) return 0 ;; *) return 1 ;; esac
}

# bun_compile_windows <description> <semver> <bun build args...>
# Runs `bun build <args>` plus the --windows-* metadata when this host can apply it.
bun_compile_windows() {
  _desc="$1"; _ver="$2"; shift 2
  if on_windows_host; then
    bun build "$@" \
      --windows-title="supermux" \
      --windows-publisher="UstaLabs" \
      --windows-description="$_desc" \
      --windows-version="$(windows_exe_version "$_ver")" \
      --windows-copyright="Copyright (c) UstaLabs" \
      --windows-icon="$ROOT/apps/desktop/icons/supermux.ico"
  else
    echo "note: cross-compiling for Windows from $(uname -s): Bun can't stamp the exe's version info here, so it keeps Bun's (the Windows release build stamps it)" >&2
    bun build "$@"
  fi
}
