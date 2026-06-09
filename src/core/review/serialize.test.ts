// src/core/review/serialize.test.ts
import { test, expect } from "bun:test"
import { serializeReview } from "./serialize"
import type { Comment } from "./store"

const c = (over: Partial<Comment>): Comment => ({
  id: "c1", sessionId: "s", repo: "", path: "src/a.ts", side: "RIGHT", anchorLine: 12,
  anchorContext: "const x = tokenize(input)", body: "rename x to token", author: "user",
  createdAt: "2026-01-01", status: "open", ...over,
})

test("groups by file and includes line, code, body", () => {
  const out = serializeReview([c({}), c({ id: "c2", anchorLine: 20, anchorContext: "return x", body: "guard null" })])
  expect(out).toContain("<code-review>")
  expect(out).toContain('<file path="src/a.ts">')
  expect(out).toContain('id="c1"')
  expect(out).toContain("const x = tokenize(input)")
  expect(out).toContain("rename x to token")
  expect(out).toContain("guard null")
})

test("empty list yields empty string", () => {
  expect(serializeReview([])).toBe("")
})

test("quotes in body and path are escaped to &quot; and &#39;", () => {
  const out = serializeReview([c({ body: 'use "quotes" and \'apostrophes\'', path: 'src/a"b.ts' })])
  expect(out).toContain("&quot;quotes&quot;")
  expect(out).toContain("&#39;apostrophes&#39;")
  expect(out).toContain("src/a&quot;b.ts")
})
