import { expect, test } from "bun:test"
import { HOST_KEY_FILE, STATE_DIR } from "./paths"

test("HOST_KEY_FILE lives under STATE_DIR", () => {
  expect(HOST_KEY_FILE).toBe(`${STATE_DIR}/host-key`)
})
