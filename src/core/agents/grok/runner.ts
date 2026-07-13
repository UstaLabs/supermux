import { spawn } from "child_process"
import type { AcpClient } from "./acp-client"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/grok/runner")

export type GrokRunner = (opts: {
  workdir: string
  env: Record<string, string>
  client: AcpClient
  onExit: (code: number | null) => void
}) => { kill: () => void }

/** Real runner: spawns `grok agent stdio`. Points the client's writes at the child's
 * stdin (appending the newline framing) and feeds the child's stdout back into the client. */
export const realGrokRunner: GrokRunner = ({ workdir, env, client, onExit }) => {
  const child = spawn("grok", ["agent", "stdio"], {
    cwd: workdir,
    env: { ...process.env, ...env },
    stdio: ["pipe", "pipe", "pipe"],
  })
  child.stdout.setEncoding("utf8")
  child.stdout.on("data", (chunk: string) => client.feed(chunk))
  child.stderr.setEncoding("utf8")
  child.stderr.on("data", (d: string) => log.debug("grok_stderr", { d: d.slice(0, 500) }))
  child.on("exit", (code) => { log.info("grok_exit", { code }); onExit(code) })
  child.on("error", (e) => { log.warn("grok_spawn_error", { err: String(e) }); onExit(null) })
  client.setWrite((line: string) => {
    if (child.stdin.writable) child.stdin.write(line + "\n")
  })
  return { kill: () => { try { child.kill("SIGTERM") } catch {} } }
}
