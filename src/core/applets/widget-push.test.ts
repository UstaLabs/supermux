import { expect, test } from "bun:test"
import { createWidgetPusher } from "./widget-push"

test("#165: a widget change reaches Android devices only, spares the device that caused it, and a burst is coalesced", async () => {
  const sent: [string, string][] = []
  let t = 1_000_000
  const p = createWidgetPusher({
    devices: () => [{ device: "fold", platform: "android" }, { device: "pixel", platform: "android" }, { device: "iphone", platform: "ios" }],
    send: async (d, payload) => { sent.push([d, payload.widget]) },
    hostId: () => "h1", gapMs: 50, now: () => t,
  })
  p.changed("issues", "fold")
  expect(sent).toEqual([["pixel", "issues"]])
  sent.length = 0
  t += 10
  p.changed("issues", "pixel")
  p.changed("issues")
  expect(sent).toEqual([])
  await Bun.sleep(80)
  t += 80
  expect(sent).toEqual([["fold", "issues"], ["pixel", "issues"]])
  p.stop()
})
