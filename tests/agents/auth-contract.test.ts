/** The normalized auth contract, in one place.
 *
 * Every per-agent resolver in `src/core/agents/<kind>/auth.ts` must:
 *  - be async (return a Promise),
 *  - return `{ mode, env }`,
 *  - follow the failure policy its file header states.
 *
 * The resolvers stay PRIVATE to their modules and share no interface. This file
 * only proves that the five agree on the shape and on the policy.
 */
import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { resolveClaudeAuth } from "../../src/core/agents/claude/auth"
import { resolveCodexAuth } from "../../src/core/agents/codex/auth"
import { resolveCursorAuth } from "../../src/core/agents/cursor/auth"
import { resolveOpenCodeAuth } from "../../src/core/agents/opencode/auth"
import { resolveGrokAuth } from "../../src/core/agents/grok/auth"

let userHome: string
let sessionHome: string

beforeEach(() => {
  userHome = mkdtempSync(join(tmpdir(), "auth-contract-user-"))
  sessionHome = mkdtempSync(join(tmpdir(), "auth-contract-session-"))
})
afterEach(() => {
  rmSync(userHome, { recursive: true, force: true })
  rmSync(sessionHome, { recursive: true, force: true })
})

/** Each resolver, invoked against a home with NO credential in it. */
function resolvers() {
  return {
    claude: () => resolveClaudeAuth({ home: userHome, platform: "linux", fileExists: () => false, runner: () => false }),
    codex: () => resolveCodexAuth({ userCodexHome: userHome, sessionCodexHome: sessionHome }),
    cursor: () => resolveCursorAuth({
      userCursorDir: join(userHome, ".cursor"), userConfigDir: join(userHome, ".config"), sessionHome,
    }),
    opencode: () => resolveOpenCodeAuth({ home: userHome, env: {}, fileExists: () => false, platform: "linux" }),
    grok: () => resolveGrokAuth({ userGrokDir: join(userHome, ".grok"), sessionHome }),
  }
}

/** Documented in each `auth.ts` header. Fail-closed means "throws when no
 * credential exists"; the child process could not work without one. */
const FAILS_CLOSED: Record<string, boolean> = {
  claude: false,   // claude reads the broker's own home; the CLI reports its own error
  codex: true,     // the app-server child cannot start without a credential
  cursor: true,    // the session would answer nothing and look like a phantom
  opencode: false, // the free opencode/* tier runs with no credential
  grok: false,     // grok reports the auth error on the first turn
}

describe("the normalized auth contract", () => {
  for (const kind of Object.keys(FAILS_CLOSED)) {
    test(`${kind}: the resolver is async`, async () => {
      const pending = resolvers()[kind as keyof ReturnType<typeof resolvers>]()
      expect(pending).toBeInstanceOf(Promise)
      await pending.catch(() => {})
    })

    test(`${kind}: fails ${FAILS_CLOSED[kind] ? "CLOSED" : "OPEN"} with no credential`, async () => {
      const pending = resolvers()[kind as keyof ReturnType<typeof resolvers>]()
      if (FAILS_CLOSED[kind]) {
        await expect(pending).rejects.toThrow()
        return
      }
      const result = await pending
      expect(typeof result.mode).toBe("string")
      expect(typeof result.env).toBe("object")
    })
  }
})
