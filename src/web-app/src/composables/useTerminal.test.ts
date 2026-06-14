import { test, expect } from "bun:test"
import { useTerminal } from "./useTerminal"

class FakeWS {
  static OPEN = 1
  readyState = 0
  binaryType = ""
  onopen: any; onclose: any; onmessage: any; onerror: any
  constructor(public url: string) { FakeWS.urls.push(url) }
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
