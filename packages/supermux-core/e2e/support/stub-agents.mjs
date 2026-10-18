// Fake agent CLIs on PATH: each name execs the matching protocol fixture with Node, so code written
// for the real CLIs (examples, README snippets) runs unchanged in CI.
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
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

export function stubAgents(extraEnv = {}) {
  const bin = mkdtempSync(join(tmpdir(), "supermux-stub-agents-"))
  for (const [name, { fixture, env }] of Object.entries(AGENTS)) {
    const vars = Object.entries({ ...env, ...extraEnv }).map(([k, v]) => `${k}=${JSON.stringify(String(v))}`).join(" ")
    const script = `#!/bin/sh\nexec env ${vars} "${process.execPath}" "${join(fixtures, fixture)}" "$@"\n`
    writeFileSync(join(bin, name), script)
    chmodSync(join(bin, name), 0o755)
  }
  return {
    bin,
    env: { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}` },
    cleanup: () => rmSync(bin, { recursive: true, force: true }),
  }
}
