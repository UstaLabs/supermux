import { test, expect } from "bun:test"
import { manualProvider } from "./manual"
import type { ConnectCtx, Run } from "./types"

// A Run that fails the test if anything tries to spawn a process — the manual
// provider must never shell out or hit the network.
const noRun: Run = async (argv) => {
  throw new Error(`manual provider must not spawn a process, got: ${argv.join(" ")}`)
}

// Minimal ConnectCtx: port 8787, no TTY, --yes, no publicUrlHint unless overridden.
function ctx(overrides: Partial<ConnectCtx> = {}): ConnectCtx {
  return {
    port: "8787",
    stateDir: "/tmp/mux-state",
    tty: false,
    yes: true,
    run: noRun,
    println: () => {},
    ask: async () => null,
    confirm: async (_p, def) => def,
    ...overrides,
  }
}

test("identity matches the contract", () => {
  expect(manualProvider.id).toBe("manual")
  expect(manualProvider.label).toBe("Self-serve (bring your own proxy/tunnel)")
  expect(manualProvider.bin).toBeUndefined()
  expect(manualProvider.modes).toEqual([
    { id: "manual", label: "I'll run my own reverse proxy / tunnel", stable: true },
  ])
})

test("detect / install / login are all no-op true", async () => {
  const c = ctx()
  expect(await manualProvider.detect(c)).toBe(true)
  expect(await manualProvider.install(c)).toBe(true)
  expect(await manualProvider.login(c)).toBe(true)
})

test("up() without a hint returns empty publicUrl + port/snippet guidance", async () => {
  const res = await manualProvider.up(ctx())

  expect(res.publicUrl).toBe("")
  expect(res.stable).toBe(true)

  const all = (res.notes ?? []).join("\n")
  // Port guidance.
  expect(all).toContain("8787")
  expect(all).toContain("http://localhost:8787")
  // Re-run hint.
  expect(all).toContain("supermux connect manual --public-url")
  // Caddy + nginx snippets, each labeled.
  expect(all).toContain("Caddy:")
  expect(all).toContain("reverse_proxy")
  expect(all).toContain("nginx:")
  expect(all).toContain("proxy_pass")
})

test("up() with --public-url echoes it back as publicUrl", async () => {
  const res = await manualProvider.up(ctx({ publicUrlHint: "https://mux.example.com" }))
  expect(res.publicUrl).toBe("https://mux.example.com")
  expect(res.stable).toBe(true)
  // The snippets use the supplied host, not the placeholder.
  const all = (res.notes ?? []).join("\n")
  expect(all).toContain("mux.example.com")
  expect(all).not.toContain("your-domain.example.com")
})

test("down() and status() are inert", async () => {
  const c = ctx()
  await expect(manualProvider.down(c)).resolves.toBeUndefined()
  expect(await manualProvider.status(c)).toEqual({ up: false })
})
