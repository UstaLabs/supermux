import { expect, test } from "bun:test"
import { pairJsonResponse } from "./pair-json"

test("returns 200 + token JSON for a pairing link's first use", async () => {
  const store = { claimPairing: (t: string) => (t === "good" ? { ok: true as const, name: "phone" } : { ok: false as const, reason: "unknown" as const }) }
  const res = pairJsonResponse("good", store)
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ token: "good", name: "phone" })
})

test("returns 401 for an unknown token, and says why for an expired or used one (#119)", async () => {
  expect(pairJsonResponse("", { claimPairing: () => ({ ok: false, reason: "unknown" }) }).status).toBe(401)
  const expired = pairJsonResponse("old", { claimPairing: () => ({ ok: false, reason: "expired" }) })
  expect(expired.status).toBe(401)
  expect(await expired.json()).toEqual({ error: "This code has expired. Make a new one.", code: "pairing_expired" })
})
