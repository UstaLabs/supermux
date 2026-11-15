// The stable `supermux` launcher agents run (#156), written next to `mux-credential` (src/core/forge/launcher.ts) and
// put first on every agent's PATH (src/core/agents/session-cli-env.ts). A source install has no `supermux` on PATH, and
// a compiled one may live anywhere: the launcher pins the binary (or the checkout) the broker runs from.
import { writeFileSync } from "fs"
import { join } from "path"
import { IS_COMPILED } from "../shared/build-info"
import { files, host, paths } from "@supermux/platform"

export function installSupermuxLauncher(binDir: string, brokerRoot: string, opts: { execPath?: string; compiled?: boolean; platform?: NodeJS.Platform } = {}): string {
  files.mkdirPrivate(binDir)
  const execPath = opts.execPath ?? process.execPath
  const compiled = opts.compiled ?? IS_COMPILED
  const platform = opts.platform ?? host.os()
  // The checkout path in the launcher's own OS's spelling (a POSIX script never gets backslashes).
  const cli = paths.flavor(platform).join(brokerRoot, "src", "cli.ts")
  if (platform === "win32") {
    const launcher = join(binDir, "supermux.cmd")
    writeFileSync(launcher, compiled ? `@"${execPath}" %*\r\n` : `@"${execPath}" "${cli}" %*\r\n`)
    return launcher
  }
  const launcher = join(binDir, "supermux")
  writeFileSync(launcher, compiled ? `#!/bin/sh\nexec ${JSON.stringify(execPath)} "$@"\n` : `#!/bin/sh\nexec ${JSON.stringify(execPath)} ${JSON.stringify(cli)} "$@"\n`)
  files.makePrivate(launcher, { executable: true })
  return launcher
}
