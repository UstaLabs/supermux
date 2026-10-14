// Fake `agy` for the antigravity driver tests. Speaks agy 1.3.4's stream-json (see
// tests/fixtures/real/antigravity): init up front, then per stdin line user_input → agent_response → result.
// argv: agy flags. env FAKE_AGY_LOG: NDJSON log of {type:"spawn",argv,home,pid} and {type:"input",content}.
// env FAKE_AGY_MODE=ineligible: one result error with conversation_id "" and no init, exit 1.
// Prompts ending in SLEEP hang in a tool until SIGINT; STUBBORN hangs and ignores SIGINT; TOOLEXIT opens
// a tool then dies without a result; LEAK prints tokens on stderr and dies; EXIT dies without a result;
// FAIL ends in a result error.
import { appendFileSync, existsSync, mkdirSync, writeFileSync } from "node:fs"
import { randomUUID } from "node:crypto"
import { join } from "node:path"
import { createInterface } from "node:readline"

const argv = process.argv.slice(2)
const flag = name => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : undefined }
const log = entry => { if (process.env.FAKE_AGY_LOG) appendFileSync(process.env.FAKE_AGY_LOG, JSON.stringify(entry) + "\n") }
const emit = frame => process.stdout.write(JSON.stringify(frame) + "\n")
log({ type: "spawn", argv, home: process.env.HOME, pid: process.pid })

if (process.env.FAKE_AGY_MODE === "ineligible") {
  emit({ event: "result", result: { conversation_id: "", status: "ERROR", response: "", error: "Eligibility check failed: Your current account is not eligible. Verify at https://antigravity.google/verify" } })
  process.exit(1)
}

// Real agy keeps each conversation in ~/.gemini/antigravity-cli/conversations/<id>.db (written at init).
// An unknown --conversation id is not adopted: a warning on stderr and a NEW conversation.
// env FAKE_AGY_FORGET=1: every --conversation id is unknown.
const conversations = join(process.env.HOME ?? "", ".gemini", "antigravity-cli", "conversations")
const requested = flag("--conversation")
const known = requested && process.env.FAKE_AGY_FORGET !== "1" && existsSync(join(conversations, `${requested}.db`))
if (requested && !known) process.stderr.write(`warning: conversation "${requested}" not found\n`)
const conversationId = known ? requested : randomUUID()
mkdirSync(conversations, { recursive: true })
writeFileSync(join(conversations, `${conversationId}.db`), "")
const permissionMode = argv.includes("--dangerously-skip-permissions") ? "bypass" : flag("--mode") ?? "request-review"
const usage = { input_tokens: 10, output_tokens: 2, thinking_tokens: 0, cache_read_tokens: 0, total_tokens: 12 }
let index = 0, turns = 0, sleeping = false, stubborn = false
const step = fields => emit({ event: "step_update", step_update: { conversation_id: conversationId, ...fields } })
const result = (status, response, error = "") =>
  emit({ event: "result", result: { conversation_id: conversationId, status, response, error, num_turns: turns, usage } })

// Real agy: SIGINT ends the turn AND the process (exit 1), the open tool never gets DONE.
process.on("SIGINT", () => {
  if (stubborn) return
  if (sleeping) result("ERROR", "", "interrupted")
  process.exit(1)
})

emit({ event: "init", conversation_id: conversationId, init: { cwd: process.cwd(), tools: ["run_command", "call_mcp_tool"], permission_mode: permissionMode } })

createInterface({ input: process.stdin }).on("line", line => {
  if (!line.trim()) return
  const content = JSON.parse(line)?.message?.content
  log({ type: "input", content })
  const text = typeof content === "string" ? content : content.filter(b => b.type === "text").map(b => b.text).join("\n\n")
  turns++
  step({ step_index: index++, state: "DONE", step_type: "user_input" })
  if (text.endsWith("SLEEP") || text.endsWith("STUBBORN")) {
    sleeping = true
    stubborn = text.endsWith("STUBBORN")
    step({ step_index: index++, state: "ACTIVE", step_type: "tool", tool_name: "run_command", tool_info: { name: "run_command", parameters: { CommandLine: "sleep 600" } } })
    return
  }
  if (text.endsWith("TOOLEXIT")) {
    step({ step_index: index++, state: "ACTIVE", step_type: "tool", tool_name: "run_command", tool_info: { name: "run_command", parameters: { CommandLine: "crash" } } })
    process.stdout.write("", () => process.exit(3))
    return
  }
  if (text.endsWith("LEAK")) {
    process.stderr.write("auth failed: access_token=ya29.a0AfB_byC1234567890abcdefghijklmnop refresh_token: 1//0gAbCdEfGhIjKlMnOpQrStUvWxYz0123456789\n", () => process.exit(3))
    return
  }
  if (text.endsWith("EXIT")) process.exit(3)
  if (text.endsWith("FAIL")) { result("ERROR", "", "model exploded"); return }
  const response = `ECHO:${text}`
  step({ step_index: index, state: "ACTIVE", step_type: "agent_response", text_delta: response })
  step({ step_index: index++, state: "DONE", step_type: "agent_response", usage })
  result("SUCCESS", response)
}).on("close", () => process.exit(0))
