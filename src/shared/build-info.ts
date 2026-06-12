// Build identity. In release builds, bun build --compile is invoked with
//   --define process.env.SUPERMUX_BUILD_VERSION='"0.2.0"'
//   --define process.env.SUPERMUX_BUILD_COMMIT='"abc1234"'
// which statically replaces these expressions. In source mode the env vars
// are unset and the dev fallbacks apply.
//
// IS_COMPILED does NOT use a define: inside a compiled binary every module
// lives in Bun's virtual filesystem, so the entry path is the ground truth
// (child processes can't read /$bunfs/ paths — call sites that hand paths to
// children must branch on this).
export const BUILD_VERSION: string = process.env.SUPERMUX_BUILD_VERSION ?? "dev"
export const BUILD_COMMIT: string = process.env.SUPERMUX_BUILD_COMMIT ?? "unknown"
export const IS_COMPILED: boolean = import.meta.path.startsWith("/$bunfs/")

export function versionString(): string {
  return `${BUILD_VERSION} (${BUILD_COMMIT})`
}
