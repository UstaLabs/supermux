// src/core/forge/launcher.ts
import { mkdirSync, writeFileSync, chmodSync } from "fs"
import { join } from "path"
import { IS_COMPILED } from "../../shared/build-info"

export interface InstallCredentialLauncherOpts {
  /** Override process.execPath (tests). */
  execPath?: string
  /** Override IS_COMPILED (tests). */
  compiled?: boolean
}

/** Write/refresh the stable `mux-credential` launcher; returns its path.
 *  Embeds the current binary so clones reference a fixed path that keeps
 *  working across broker restarts (even from a different worktree).
 *
 *  Compiled installs cannot hand children a `/$bunfs/.../credential-cli.ts`
 *  path (virtual FS is not readable outside the process), so they launch
 *  `supermux credential` — same pattern as the MCP shim subcommand. */
export function installCredentialLauncher(
  binDir: string,
  brokerRoot: string,
  opts: InstallCredentialLauncherOpts = {},
): string {
  mkdirSync(binDir, { recursive: true, mode: 0o700 })
  const launcher = join(binDir, "mux-credential")
  const execPath = opts.execPath ?? process.execPath
  const compiled = opts.compiled ?? IS_COMPILED
  const body = compiled
    ? `#!/bin/sh\nexec ${JSON.stringify(execPath)} credential "$@"\n`
    : `#!/bin/sh\nexec ${JSON.stringify(execPath)} ${JSON.stringify(join(brokerRoot, "src", "core", "forge", "credential-cli.ts"))} "$@"\n`
  writeFileSync(launcher, body)
  chmodSync(launcher, 0o700)
  return launcher
}
