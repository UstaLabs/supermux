// src/core/forge/launcher.ts
import { mkdirSync, writeFileSync, chmodSync } from "fs"
import { join } from "path"

/** Write/refresh the stable `mux-credential` launcher; returns its path.
 *  Embeds the current bun binary + broker repo so clones reference a fixed path
 *  that keeps working across broker restarts (even from a different worktree). */
export function installCredentialLauncher(binDir: string, brokerRoot: string): string {
  mkdirSync(binDir, { recursive: true, mode: 0o700 })
  const launcher = join(binDir, "mux-credential")
  const bun = process.execPath
  const cli = join(brokerRoot, "src", "core", "forge", "credential-cli.ts")
  writeFileSync(launcher, `#!/bin/sh\nexec ${JSON.stringify(bun)} ${JSON.stringify(cli)} "$@"\n`)
  chmodSync(launcher, 0o700)
  return launcher
}
