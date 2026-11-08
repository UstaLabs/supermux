import { realpathSync, statSync } from "fs"
import { resolveCommand, spawnCommand } from "../../process/launcher"

// Quick "does cursor-agent run at all" check. Confirms the binary is on PATH
// and the env-isolated HOME is readable. Anything else (auth failures, model
// access) will surface on the first user message — which is the right time.
export async function smokeCursorAgent(opts: { home: string; authEnv: Record<string, string> }): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const env: Record<string, string> = {
      ...(process.env as Record<string, string>),
      ...opts.authEnv,
      HOME: opts.home,
      ...(process.platform === "win32" ? { USERPROFILE: opts.home } : {}),
    }
    const command = resolveCommand(["cursor-agent"], env, process.platform) ?? "cursor-agent"
    const child = spawnCommand(command, ["--version"], { env, stdio: ["ignore", "pipe", "pipe"] })
    let out = ""
    child.stdout!.on("data", (c: Buffer) => { out += c.toString("utf8") })
    child.stderr!.on("data", () => {})  // drain
    child.on("exit", (code) => {
      if (code === 0) resolve()
      else reject(new Error(`cursor-agent --version exit ${code}; output: ${out.slice(0, 200)}`))
    })
    child.on("error", (err) => reject(new Error(`cursor-agent not runnable: ${err.message}`)))
  })
}

type SmokeOptions = { home: string; authEnv: Record<string, string> }

/**
 * [run] (smokeCursorAgent), remembered per binary (#102): `cursor-agent --version` loads its whole bundle (2.6–3.4 s
 * alone, 6–7 s under load) and ran on every Cursor launch. Once a binary (its real path, size and mtime) has passed,
 * the same binary is not asked again; a failure is never remembered.
 */
export function cachedCursorSmoke(run: (opts: SmokeOptions) => Promise<void> = smokeCursorAgent): (opts: SmokeOptions) => Promise<void> {
  const passed = new Set<string>()
  return async (opts) => {
    const key = binaryKey({ ...(process.env as Record<string, string>), ...opts.authEnv, HOME: opts.home })
    if (key !== undefined && passed.has(key)) return
    await run(opts)
    if (key !== undefined) passed.add(key)
  }
}

function binaryKey(env: Record<string, string>): string | undefined {
  const command = resolveCommand(["cursor-agent"], env)
  if (!command) return undefined
  try {
    const binary = realpathSync(command)
    const st = statSync(binary)
    return `${binary}\0${st.size}\0${st.mtimeMs}`
  } catch {
    return undefined
  }
}
