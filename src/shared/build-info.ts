// Build identity. In release builds, bun build --compile is invoked with
//   --define process.env.SUPERMUX_BUILD_VERSION='"0.2.0"'
//   --define process.env.SUPERMUX_BUILD_COMMIT='"abc1234"'
// which statically replaces these expressions. In source mode the env vars
// are unset and the dev fallbacks apply.
//
// IS_COMPILED does NOT use a define: inside a compiled binary every module
// lives in Bun's virtual filesystem, so the entry path is the ground truth
// (child processes can't read /$bunfs/ paths — call sites that hand paths to
// children must branch on this). That root is `/$bunfs/` on macOS/Linux and
// `B:\~BUN\` on Windows (verified on Windows 11: `B:\~BUN\root\p.exe`).
export const BUILD_VERSION: string = process.env.SUPERMUX_BUILD_VERSION ?? "dev"
export const BUILD_COMMIT: string = process.env.SUPERMUX_BUILD_COMMIT ?? "unknown"

/** Is [modulePath] inside a compiled binary's virtual filesystem (any OS)? */
export function isCompiledModulePath(modulePath: string): boolean {
  return modulePath.startsWith("/$bunfs/") || /^[A-Za-z]:[\\/]~BUN[\\/]/.test(modulePath)
}

export const IS_COMPILED: boolean = isCompiledModulePath(import.meta.path)

export function versionString(): string {
  return `${BUILD_VERSION} (${BUILD_COMMIT})`
}
