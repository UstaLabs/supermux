import { test, expect } from "bun:test"
import { useTerminal } from "./useTerminal"

class FakeWS {
  static OPEN = 1
  static instances: FakeWS[] = []
  readyState = 0
  binaryType = ""
  onopen: any; onclose: any; onmessage: any; onerror: any
  constructor(public url: string) { FakeWS.urls.push(url); FakeWS.instances.push(this) }
  send() {}
  close() {}
  static urls: string[] = []
}

test("agent kind adds kind=agent to the ws url; scratch omits it", () => {
  FakeWS.urls = []
  ;(globalThis as any).WebSocket = FakeWS as any
  ;(globalThis as any).window = { location: { protocol: "http:", host: "h" } }

  const agent = useTerminal(() => "sess", () => "agent", () => "agent")
  agent.connect()
  expect(FakeWS.urls[0]).toContain("/ws/term?session=sess&terminal=agent")
  expect(FakeWS.urls[0]).toContain("kind=agent")
  agent.disconnect()

  const scratch = useTerminal(() => "sess", () => "t1")
  scratch.connect()
  expect(FakeWS.urls[1]).toContain("/ws/term?session=sess&terminal=t1")
  expect(FakeWS.urls[1]).not.toContain("kind=")
  scratch.disconnect()
})

test("re-sends the pending resize once the socket opens", () => {
  class RecordingWS {
    static OPEN = 1
    static instances: RecordingWS[] = []
    readyState = 0
    binaryType = ""
    onopen: any; onclose: any; onmessage: any; onerror: any
    sent: string[] = []
    constructor(public url: string) { RecordingWS.instances.push(this) }
    send(data: any) { this.sent.push(String(data)) }
    close() { this.readyState = 3 }
  }
  ;(globalThis as any).WebSocket = RecordingWS as any
  ;(globalThis as any).window = { location: { protocol: "http:", host: "h" } }

  const term = useTerminal(() => "sess", () => "agent", () => "agent")
  term.connect()
  const ws = RecordingWS.instances.at(-1)!

  // fit() typically runs before the socket is open, so a size requested while
  // CONNECTING must be remembered, not dropped — nothing is sent yet.
  term.resize(73, 50)
  expect(ws.sent).toEqual([])

  // Once the socket opens, the remembered size is flushed exactly once. Without
  // this the agent terminal stays at the initial 80x24 and TUI redraws garble.
  ws.readyState = RecordingWS.OPEN
  ws.onopen?.()
  expect(ws.sent).toEqual([JSON.stringify({ type: "resize", cols: 73, rows: 50 })])

  term.disconnect()
})

test("distinguishes reset, attach error, and a transport close", () => {
  FakeWS.urls = []
  FakeWS.instances = []
  ;(globalThis as any).WebSocket = FakeWS as any
  ;(globalThis as any).window = { location: { protocol: "http:", host: "h" } }
  const term = useTerminal(() => "sess", () => "t1")
  const events: string[] = []
  term.onReset(() => { events.push("reset") })
  term.onData(data => { events.push(new TextDecoder().decode(data)) })
  term.onExit(code => { events.push(`exit:${code}`) })
  term.connect()
  const ws = FakeWS.instances[0]!
  ws.onmessage?.({ data: JSON.stringify({ type: "reset" }) })
  ws.onmessage?.({ data: new TextEncoder().encode("snapshot").buffer })
  ws.onclose?.()
  expect(events).toEqual(["reset", "snapshot"])
  ws.onmessage?.({ data: JSON.stringify({ type: "error", reason: "attach rejected" }) })
  expect(events).toEqual(["reset", "snapshot", "exit:-1"])
  term.disconnect()
})
