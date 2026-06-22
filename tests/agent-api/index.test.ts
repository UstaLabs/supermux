import { test, expect } from "bun:test"
import { select, ENGINES, VOICE_CLEANUP_ENGINE } from "../../src/core/agent-api/index"

test("select maps each engine string to the right adapter name", () => {
  expect(select("codex").name).toBe("codex")
  expect(select("opencode-zen").name).toBe("opencode-zen")
  expect(select("opencode-go").name).toBe("opencode-go")
  expect(select("claude").name).toBe("claude")
  expect(select("cursor-cli").name).toBe("cursor-cli")
  // cursor maps to the cursor-cli adapter until the protobuf adapter (Task 7) lands.
  expect(select("cursor").name).toBe("cursor-cli")
})

test("select defaults an unknown engine to codex", () => {
  expect(select("nope" as any).name).toBe("codex")
})

test("ENGINES lists every supported engine string", () => {
  expect(ENGINES).toEqual(["codex", "opencode-zen", "opencode-go", "claude", "cursor", "cursor-cli"])
})

test("VOICE_CLEANUP_ENGINE defaults to codex", () => {
  // No env / config override in the test process → codex.
  expect(VOICE_CLEANUP_ENGINE).toBe("codex")
})

test("select forwards an injected fetchFn to the opencode adapter", async () => {
  let hit: string | undefined
  const fetchFn = (async (url: string) => {
    hit = String(url)
    return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }] }), { status: 200 })
  }) as unknown as typeof fetch
  const a = select("opencode-zen", { fetchFn, readFileFn: () => JSON.stringify({ opencode: { key: "k" } }) })
  const out = await a.complete("hi")
  expect(out).toBe("ok")
  expect(hit).toContain("opencode.ai/zen")
})

test("select forwards an injected run to the cursor-cli adapter", async () => {
  let argv: string[] | undefined
  const a = select("cursor-cli", {
    run: async (a2) => {
      argv = a2
      return { code: 0, out: "fixed" }
    },
  })
  const out = await a.complete("hi")
  expect(out).toBe("fixed")
  expect(argv?.[0]).toBe("cursor-agent")
})
