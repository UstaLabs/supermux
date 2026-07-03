import { describe, expect, test } from "bun:test"
import { extFromMime } from "./mime"

describe("extFromMime", () => {
  test("new video container types map to real extensions", () => {
    expect(extFromMime("video/quicktime")).toBe("mov")
    expect(extFromMime("video/x-matroska")).toBe("mkv")
    expect(extFromMime("video/x-m4v")).toBe("m4v")
  })

  test("existing video types unchanged", () => {
    expect(extFromMime("video/mp4")).toBe("mp4")
    expect(extFromMime("video/webm")).toBe("webm")
  })

  test("unknown/undefined → bin", () => {
    expect(extFromMime("video/3gpp")).toBe("bin")
    expect(extFromMime(undefined)).toBe("bin")
  })
})
