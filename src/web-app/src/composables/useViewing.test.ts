import { test, expect, beforeEach, afterEach, mock } from "bun:test"

// ── Mock the composable's collaborators ──────────────────────────────────────
// useViewing pulls the current route (which session is open) and the ws (to send
// frames). We stub both so we can drive the route/visibility and capture sends.
let routePath = "/s/abc-123"
let routeId: string | null = "abc-123"
const sent: any[] = []
let wsStatus = "connected"

mock.module("vue-router", () => ({
  useRoute: () => ({
    get path() { return routePath },
    params: { get id() { return routeId } },
  }),
}))

mock.module("../api/ws", () => ({
  useWS: () => ({
    get status() { return wsStatus },
    send: (frame: any) => { sent.push(frame) },
  }),
}))

// ── Controllable timers + document/window shims ──────────────────────────────
let intervalCb: (() => void) | null = null
let intervalMs = 0
const timeouts: Array<() => void> = []
let visibility = "visible"

beforeEach(() => {
  sent.length = 0
  timeouts.length = 0
  intervalCb = null
  intervalMs = 0
  routePath = "/s/abc-123"
  routeId = "abc-123"
  wsStatus = "connected"
  visibility = "visible"

  ;(globalThis as any).document = {
    get visibilityState() { return visibility },
    addEventListener: () => {},
    removeEventListener: () => {},
    // @vue/runtime-dom touches createElement at import time; useViewing never
    // renders, so a dummy element is enough to get past module init.
    createElement: () => ({}),
    createElementNS: () => ({}),
  }
  ;(globalThis as any).window = {
    setTimeout: (cb: () => void) => { timeouts.push(cb); return timeouts.length },
    clearTimeout: () => {},
    setInterval: (cb: () => void, ms: number) => { intervalCb = cb; intervalMs = ms; return 1 },
    clearInterval: () => { intervalCb = null },
  }
})

afterEach(() => {
  delete (globalThis as any).document
  delete (globalThis as any).window
})

function flushTimeouts() {
  // useViewing debounces its event-driven send via setTimeout(flush, 50)
  while (timeouts.length) timeouts.shift()!()
}

test("re-sends the viewing frame on a heartbeat so the server's viewing TTL never lapses", async () => {
  const { effectScope } = await import("vue")
  const { useViewing } = await import("./useViewing")
  const scope = effectScope()
  scope.run(() => useViewing()) // App.vue runs it inside a setup scope
  flushTimeouts() // initial event-driven send

  const initialSends = sent.filter((f) => f.type === "viewing").length
  expect(initialSends).toBeGreaterThanOrEqual(1)

  // A heartbeat must be registered, and it must fire well under the 5-min server TTL.
  expect(intervalCb).not.toBeNull()
  expect(intervalMs).toBeLessThan(5 * 60_000)

  // Simulate the user just sitting on the chat: no route/visibility change.
  // The heartbeat alone must keep re-asserting "I'm viewing editor".
  intervalCb!()
  const heartbeatSends = sent.filter((f) => f.type === "viewing" && f.session === "abc-123" && f.visible === true)
  expect(heartbeatSends.length).toBeGreaterThanOrEqual(initialSends + 1)
})
