import { describe, expect, test } from "bun:test"
import { BUILD_VERSION, BUILD_COMMIT, IS_COMPILED, isCompiledModulePath, versionString } from "./build-info"

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

  test("compiled module paths on every OS", () => {
    expect(isCompiledModulePath("/$bunfs/root/supermux")).toBe(true)
    // Windows: what import.meta.path is inside a compiled .exe (observed on Windows 11).
    expect(isCompiledModulePath("B:\\~BUN\\root\\supermux-broker.exe")).toBe(true)
    expect(isCompiledModulePath("B:/~BUN/root/src/shared/build-info.ts")).toBe(true)
    expect(isCompiledModulePath("/home/u/supermux/src/shared/build-info.ts")).toBe(false)
    expect(isCompiledModulePath("C:\\Users\\u\\supermux\\src\\shared\\build-info.ts")).toBe(false)
    expect(isCompiledModulePath("C:\\~BUNNY\\x.ts")).toBe(false)
  })
})
