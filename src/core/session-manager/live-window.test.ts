import { describe, expect, test } from "bun:test"
import { liveWindowId } from "./live-window"

describe("liveWindowId", () => {
  test("returns the registered id and does not consult the pending map", () => {
    let pendingConsulted = false
    const wid = liveWindowId(
      "sid",
      () => "@10",
      () => { pendingConsulted = true; return "@99" },
    )
    expect(wid).toBe("@10")
    expect(pendingConsulted).toBe(false)
  })

  test("falls back to the pending id when not yet registered", () => {
    const wid = liveWindowId(
      "sid",
      () => undefined,
      () => "@42",
    )
    expect(wid).toBe("@42")
  })

  test("returns undefined when neither registry nor pending has an id", () => {
    const wid = liveWindowId(
      "sid",
      () => undefined,
      () => undefined,
    )
    expect(wid).toBeUndefined()
  })
})
