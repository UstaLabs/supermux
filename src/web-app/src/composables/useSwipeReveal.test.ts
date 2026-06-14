import { test, expect, describe } from "bun:test"
import { resolveSwipeTarget } from "./useSwipeReveal"

// Geometry mirrors the SwipeableSessionRow caller: left buttons 140px, right 80px.
// `offset` is cumulative px: > 0 reveals the LEFT-side buttons (state "open-right"),
// < 0 reveals the RIGHT-side button (state "open-left"). `velocity` is SIGNED px/ms
// (positive = finger moving right). `threshold` is a fraction of the side width;
// `velocityThreshold` is a flick speed in px/ms.
const GEO = { leftWidth: 140, rightWidth: 80, threshold: 0.3, velocityThreshold: 0.3 }

describe("resolveSwipeTarget — opening from rest", () => {
  test("small slow drag below the distance threshold stays closed", () => {
    // 20px < 140*0.3 = 42px, negligible velocity
    expect(resolveSwipeTarget(20, 0.0, GEO)).toEqual({ target: 0, state: "idle" })
  })

  test("slow drag past the distance threshold opens the left buttons", () => {
    // 60px > 42px, negligible velocity
    expect(resolveSwipeTarget(60, 0.0, GEO)).toEqual({ target: 140, state: "open-right" })
  })

  test("fast right flick opens the left buttons even when short", () => {
    expect(resolveSwipeTarget(15, 0.6, GEO)).toEqual({ target: 140, state: "open-right" })
  })

  test("fast left flick opens the right button even when short", () => {
    expect(resolveSwipeTarget(-15, -0.6, GEO)).toEqual({ target: -80, state: "open-left" })
  })
})

describe("resolveSwipeTarget — getting back to the original (closed) state", () => {
  test("right flick from an open-left row CLOSES it (does not open the opposite side)", () => {
    // Row is open-left at offset -80; user flicks right to dismiss.
    // The bug made this open the opposite side; it must close instead.
    expect(resolveSwipeTarget(-80, 0.6, GEO)).toEqual({ target: 0, state: "idle" })
  })

  test("left flick from an open-right row CLOSES it (does not open the opposite side)", () => {
    expect(resolveSwipeTarget(140, -0.6, GEO)).toEqual({ target: 0, state: "idle" })
  })

  test("slow drag back past the threshold closes an open-left row", () => {
    // Dragged from -80 back to -10; |−10| < 80*0.3 = 24, so it settles closed.
    expect(resolveSwipeTarget(-10, 0.0, GEO)).toEqual({ target: 0, state: "idle" })
  })
})

describe("resolveSwipeTarget — staying open", () => {
  test("tiny nudge (below flick speed) on an open-left row keeps it open", () => {
    expect(resolveSwipeTarget(-80, -0.1, GEO)).toEqual({ target: -80, state: "open-left" })
  })

  test("flicking further in the same direction keeps an open-left row open", () => {
    expect(resolveSwipeTarget(-80, -0.6, GEO)).toEqual({ target: -80, state: "open-left" })
  })
})
