import { expect, test } from "bun:test"
import { HOST_KEY_FILE, SESSION_INBOX_DIR, STATE_DIR, inboxFilePath } from "./paths"

test("HOST_KEY_FILE lives under STATE_DIR", () => {
  expect(HOST_KEY_FILE).toBe(`${STATE_DIR}/host-key`)
})

test("inboxFilePath is <dir>/<session id>.jsonl, in the session inbox folder unless another is given", () => {
  expect(inboxFilePath("7c1d2e9a-0000")).toBe(`${SESSION_INBOX_DIR}/7c1d2e9a-0000.jsonl`)
  expect(inboxFilePath("7c1d2e9a-0000", "/data/inboxes")).toBe("/data/inboxes/7c1d2e9a-0000.jsonl")
})
