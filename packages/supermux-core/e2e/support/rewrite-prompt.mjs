// Runs a stub agent with this script between it and the keeper, and asks it PROXY_PROMPT whatever a turn says. A recipe
// whose prompt is fixed can then be given any turn the stub knows ("permission", "fail", ...).
//
//   node rewrite-prompt.mjs <stub.mjs> [args...]      (the stub gets the args as they are; PROXY_PROMPT is in the env)
import { spawn } from "node:child_process"
import { createInterface } from "node:readline"

const prompt = process.env.PROXY_PROMPT
const child = spawn(process.execPath, process.argv.slice(2), { stdio: ["pipe", "pipe", "inherit"] })

/** A Codex `turn/start` request with its text replaced; any other line as it is. */
function rewrite(line) {
  try {
    const message = JSON.parse(line)
    if (prompt === undefined || message.method !== "turn/start") return line
    message.params.input[0].text = prompt
    return JSON.stringify(message)
  } catch {
    return line
  }
}

const fromKeeper = createInterface({ input: process.stdin })
fromKeeper.on("line", line => child.stdin.write(`${rewrite(line)}\n`))
fromKeeper.on("close", () => child.stdin.end())
createInterface({ input: child.stdout }).on("line", line => process.stdout.write(`${line}\n`))
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill(signal))
child.on("close", (code, signal) => process.exit(code ?? (signal ? 1 : 0)))
