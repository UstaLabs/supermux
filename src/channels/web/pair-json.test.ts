import { expect, test } from "bun:test"
import { pairJsonResponse } from "./pair-json"

test("returns 200 + token JSON for a valid pairing token", async () => {
  const store = { verify: (t: string) => (t === "good" ? { name: "phone" } : undefined) }
  const res = pairJsonResponse("good", store as any)
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ token: "good", name: "phone" })
})

test("returns 401 for an invalid token", () => {
  const store = { verify: () => undefined }
  expect(pairJsonResponse("bad", store as any).status).toBe(401)
})
