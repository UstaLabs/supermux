import { test, expect, beforeEach, afterEach } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { ref } from "vue"
import { useForgeOmnibox } from "./useForgeOmnibox"

beforeEach(() => setActivePinia(createPinia()))
const realFetch = globalThis.fetch
afterEach(() => { globalThis.fetch = realFetch })
function mock(localPath: string) {
  globalThis.fetch = (async () => new Response(JSON.stringify({ localPath }), { status: 200, headers: { "content-type": "application/json" } })) as any
}

test("resolve returns the local path directly for a local option", async () => {
  const o = useForgeOmnibox(ref(""))
  expect(await o.resolve({ kind: "local", label: "x", path: "/p/x" })).toBe("/p/x")
})
test("resolve clones a cloud option", async () => {
  mock("/cloned/x")
  const o = useForgeOmnibox(ref(""))
  const repo = { connectionId: "c", kind: "github", host: "github.com", owner: "a", name: "x", fullName: "a/x", private: false, defaultBranch: "main", cloneUrl: "", webUrl: "" } as any
  expect(await o.resolve({ kind: "cloud", label: "a/x", connectionId: "c", repo })).toBe("/cloned/x")
})
test("resolve creates locally", async () => {
  mock("/local/new")
  const o = useForgeOmnibox(ref("new"))
  expect(await o.resolve({ kind: "create", label: "Create locally — new", createTarget: "local" })).toBe("/local/new")
})
