// Runs the code blocks of a Markdown file the way its reader would. A block whose fence says ```ts run (GitHub still
// renders it as TypeScript) is written into this package's .snippets/ folder, where `import … from "@supermux/core"`
// resolves to the package itself by its own name, and run with Node (type stripping: erasable syntax only) from an
// empty directory of its own, against the stub agent CLIs of stub-agents.mjs.
//
// A block passes when it exits 0, its stdout matches each `// expect-stdout: <regex>` line it ends with (^ and $ match
// at line ends), and every agent it names in `agents: { … }` had its stub run.
import { spawn } from "node:child_process"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmdirSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { basename, dirname, extname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { stubAgents } from "./stub-agents.mjs"

/** Where the blocks are written: inside the package, so that its own name resolves. Each run removes what it wrote. */
export const SNIPPETS = fileURLToPath(new URL("../../.snippets/", import.meta.url))

const INFO = "ts run"
const EXPECT = /^\/\/ expect-stdout: (.+)$/
// The agents supermux() takes, by the name of the CLI each one runs. Antigravity's agy has no stub.
const CLI = { claude: "claude", codex: "codex", grok: "grok", opencode: "opencode", cursor: "cursor-agent", antigravity: "agy" }

/**
 * The ```ts run blocks of `markdown`, in order: `{ number, line, code, expectStdout }`. `number` counts these blocks
 * from 1, `line` is the line of the opening fence, and `expectStdout` holds the regexes of the `// expect-stdout:`
 * lines the block ends with.
 */
export function runnableBlocks(markdown) {
  const blocks = []
  let open
  markdown.split(/\r?\n/).forEach((text, index) => {
    if (!open) {
      // A fence of three or more backticks or tildes, indented up to three spaces; a backtick fence's info has none.
      const fence = /^( {0,3})(`{3,}|~{3,})(.*)$/.exec(text)
      if (fence && !(fence[2][0] === "`" && fence[3].includes("`"))) {
        open = { indent: fence[1].length, fence: fence[2], info: fence[3].trim(), line: index + 1, body: [] }
      }
      return
    }
    const close = /^ {0,3}(`{3,}|~{3,})\s*$/.exec(text)
    if (close && close[1][0] === open.fence[0] && close[1].length >= open.fence.length) {
      if (open.info === INFO) blocks.push(blockOf(blocks.length + 1, open))
      open = undefined
    } else {
      open.body.push(text.slice(Math.min(open.indent, /^ */.exec(text)[0].length)))
    }
  })
  // A fence left open runs to the end of the document.
  if (open?.info === INFO) blocks.push(blockOf(blocks.length + 1, open))
  return blocks
}

function blockOf(number, { line, body }) {
  const lines = body.map(text => text.trim())
  while (lines.length && !lines.at(-1)) lines.pop()
  const expectStdout = []
  while (lines.length && EXPECT.test(lines.at(-1))) expectStdout.unshift(new RegExp(EXPECT.exec(lines.pop())[1], "m"))
  return { number, line, code: body.join("\n") + "\n", expectStdout }
}

/** The agents `code` names as keys of `agents: { … }`, in order (best effort: a computed key or a variable is not seen). */
export function namedAgents(code) {
  const named = new Set()
  for (const match of code.matchAll(/\bagents\s*:\s*\{/g)) {
    // The object's own text: what is left with every nested {…}, […] and (…) taken out.
    let depth = 0
    let own = ""
    for (const char of code.slice(match.index + match[0].length - 1)) {
      if ("{[(".includes(char)) depth++
      else if ("}])".includes(char)) depth--
      else if (depth === 1) own += char
      if (!depth) break
    }
    for (const [, key] of own.matchAll(/(\w+)\s*(?=[:,]|$)/g)) if (Object.hasOwn(CLI, key)) named.add(key)
  }
  return [...named]
}

/**
 * Runs `block` (from runnableBlocks) of the Markdown file `file`, the name failures give. Resolves with
 * `{ stdout, stderr, launched }`, `launched` naming the stub CLIs that ran; rejects with an Error that names the file,
 * the block and its line, and holds what the block printed. Whatever happens, nothing it started is left running and
 * nothing it wrote is left behind.
 */
export async function runBlock(file, block, { timeoutMs = 60_000 } = {}) {
  const script = join(SNIPPETS, `${basename(file, extname(file))}-${block.number}.ts`)
  // The Claude stub starts the stdio MCP servers it is given, as Claude does, so a block can give it host tools.
  const stubs = stubAgents({ FIXTURE_MCP: "1" })
  let root
  let result
  let launched
  try {
    root = mkdtempSync(join(tmpdir(), "snippet-"))
    const cwd = join(root, "cwd")
    const tmp = join(root, "tmp")
    mkdirSync(cwd)
    mkdirSync(tmp)
    write(script, block.code)
    // What the block makes in os.tmpdir() lands under root too, to be found and removed.
    result = await execute(script, { cwd, env: { ...stubs.env, TMPDIR: tmp }, timeoutMs })
    launched = stubs.launched()
  } finally {
    try {
      if (root) killKeepers(root)
    } finally {
      stubs.cleanup()
      if (root) rmSync(root, { recursive: true, force: true })
      rmSync(script, { force: true })
      try { rmdirSync(SNIPPETS) } catch { /* another run still uses it, or it is gone */ }
    }
  }
  const { code, signal, timedOut, stdout, stderr } = result
  const failures = []
  if (timedOut) failures.push(`did not finish in ${timeoutMs / 1000} s`)
  else if (code !== 0) failures.push(signal ? `was killed by ${signal}` : `exited with code ${code}`)
  else {
    for (const pattern of block.expectStdout) if (!pattern.test(stdout)) failures.push(`its stdout does not match ${pattern}`)
    for (const agent of namedAgents(block.code)) {
      if (!launched.includes(CLI[agent])) failures.push(`it names ${agent} in agents: { … }, but the ${CLI[agent]} stub never ran`)
    }
  }
  if (failures.length) throw new Error(`${file} block ${block.number} (line ${block.line}): ${failures.join("; ")}\nstdout:\n${stdout}\nstderr:\n${stderr}`)
  return { stdout, stderr, launched }
}

function write(script, code) {
  // A run that has just finished may remove the empty folder between the two calls: then once more.
  for (let attempt = 1; ; attempt++) {
    mkdirSync(SNIPPETS, { recursive: true })
    try {
      return writeFileSync(script, code)
    } catch (error) {
      if (error.code !== "ENOENT" || attempt === 3) throw error
    }
  }
}

/** `node <script>` in a process group of its own, so that what it leaves behind on exit or at the timeout is killed. */
function execute(script, { cwd, env, timeoutMs }) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script], { cwd, env, detached: true, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = ""
    let stderr = ""
    let timedOut = false
    child.stdout.setEncoding("utf8").on("data", chunk => { stdout += chunk })
    child.stderr.setEncoding("utf8").on("data", chunk => { stderr += chunk })
    const killGroup = () => {
      try { process.kill(-child.pid, "SIGKILL") } catch { /* the group is empty */ }
    }
    const timer = setTimeout(() => { timedOut = true; killGroup() }, timeoutMs)
    // A process of its group still holding the pipes would keep "close" from coming.
    child.once("exit", killGroup)
    child.once("error", error => { clearTimeout(timer); reject(error) })
    child.once("close", (code, signal) => { clearTimeout(timer); resolve({ code, signal, timedOut, stdout, stderr }) })
  })
}

/**
 * Kills every keeper under `dir` that is still running, with its agent: a block that failed before it closed its core,
 * or one that detached, leaves them behind, and a keeper never ends while its agent runs. A keeper removes its socket
 * when it ends, so one whose socket is still there is running (or was killed outright).
 */
function killKeepers(dir) {
  for (const path of readdirSync(dir, { recursive: true })) {
    if (!/(^|[\\/])keepers[\\/][^\\/]+[\\/]status\.json$/.test(path)) continue
    const keeper = dirname(join(dir, path))
    if (!existsSync(join(keeper, "keeper.sock"))) continue
    let status
    try { status = JSON.parse(readFileSync(join(keeper, "status.json"), "utf8")) } catch { continue }
    // The keeper leads a process group of its own, which its agent and what the agent starts belong to.
    for (const pid of [-status.keeperPid, status.keeperPid, status.agentPid]) {
      if (Number.isSafeInteger(pid) && pid !== 0) try { process.kill(pid, "SIGKILL") } catch { /* already gone */ }
    }
  }
}
