import { describe, expect, test } from "bun:test"
import { BUILD_VERSION, BUILD_COMMIT, IS_COMPILED, versionString } from "./build-info"

describe("build-info", () => {
  test("source mode: dev fallbacks", () => {
    // Under `bun test` nothing is compiled and no defines are set.
    expect(IS_COMPILED).toBe(false)
    expect(BUILD_VERSION).toBe("dev")
    expect(BUILD_COMMIT).toBe("unknown")
  })

  test("versionString combines version and commit", () => {
    expect(versionString()).toBe("dev (unknown)")
  })
})
