// Fake agent CLIs on PATH: each name execs the matching protocol fixture with Node, so code written
// for the real CLIs (examples, README snippets) runs unchanged in CI.
//
// Run the code under test with `env`: its PATH holds the stubs and the system directories only, so a
// name whose stub cannot run (a noexec TMPDIR, say) fails instead of reaching a real CLI of the same
// name; its HOME is an empty directory of its own, so nothing touches the real one. Each stub also
// records that it ran (`launched()`), which is how a test knows the stub gave the answer.
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { delimiter, join } from "node:path"
import { fileURLToPath } from "node:url"

const fixtures = fileURLToPath(new URL("../../tests/fixtures/", import.meta.url))

// The env each fixture needs to accept what supermux() launches it with.
const AGENTS = {
  claude: { fixture: "claude-agent.mjs", env: { EXPECT_DEFAULT_DENY: "0" } },
  codex: { fixture: "codex-agent.mjs", env: { EXPECT_POLICY: "on-request", EXPECT_SANDBOX: "workspace-write" } },
  grok: { fixture: "grok-agent.mjs", env: {} },
  opencode: { fixture: "acp-agent.mjs", env: {} },
  "cursor-agent": { fixture: "cursor-agent.mjs", env: {} },
}

/** `value` as one sh word: in single quotes, where nothing is special but the quote itself, written '\''. */
const quote = value => `'${String(value).replaceAll("'", `'\\''`)}'`

export function stubAgents(extraEnv = {}) {
  const root = mkdtempSync(join(tmpdir(), "supermux-stub-agents-"))
  const cleanup = () => rmSync(root, { recursive: true, force: true })
  try {
    const bin = join(root, "bin")
    const home = join(root, "home")
    const log = join(root, "launched")
    mkdirSync(bin)
    mkdirSync(home)
    for (const [name, { fixture, env }] of Object.entries(AGENTS)) {
      const vars = Object.entries({ ...env, ...extraEnv }).map(([key, value]) => quote(`${key}=${value}`))
      const command = [...vars, quote(process.execPath), quote(join(fixtures, fixture))].join(" ")
      // The marker comes first, before anything that could fail.
      const script = `#!/bin/sh\nprintf '%s\\n' ${quote(name)} >> ${quote(log)}\nexec env ${command} "$@"\n`
      writeFileSync(join(bin, name), script)
      chmodSync(join(bin, name), 0o755)
    }
    return {
      bin,
      home,
      /** The file each stub appends its name to when it runs. */
      log,
      env: { ...process.env, HOME: home, PATH: [bin, "/usr/bin", "/bin"].join(delimiter) },
      /** The names of the stubs that have run, in order. */
      launched: () => (existsSync(log) ? readFileSync(log, "utf8").split("\n").filter(Boolean) : []),
      cleanup,
    }
  } catch (error) {
    cleanup()
    throw error
  }
}
