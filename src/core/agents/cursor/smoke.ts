import { resolveCommand, spawnCommand } from "../../process/launcher"

// Quick "does cursor-agent run at all" check. Cursor is per-turn so we
// don't pre-launch a real agent; this just confirms the binary is on PATH
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
    const command = resolveCommand(["cursor-agent", "agent"], env, process.platform) ?? "cursor-agent"
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
