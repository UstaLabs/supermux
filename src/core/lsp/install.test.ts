import { test, expect } from "bun:test"
import { installEnv } from "./install"

test("installEnv is non-interactive and sets GOBIN under mux lsp", () => {
  const env = installEnv()
  expect(env.DEBIAN_FRONTEND).toBe("noninteractive")
  expect(env.CI).toBe("true")
  expect(env.GOBIN).toContain(".mux/lsp/bin")
  expect(env.PATH).toContain(".mux/lsp/bin")
})
