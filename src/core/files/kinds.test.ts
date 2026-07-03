import { describe, expect, test } from "bun:test"
import { kindFromMime } from "./kinds"

describe("kindFromMime", () => {
  test("video/* → 'video' (was video_note)", () => {
    expect(kindFromMime("video/mp4")).toBe("video")
    expect(kindFromMime("video/quicktime")).toBe("video")
    expect(kindFromMime("video/webm")).toBe("video")
  })

  test("non-video mappings unchanged", () => {
    expect(kindFromMime("image/png")).toBe("photo")
    expect(kindFromMime("audio/ogg")).toBe("audio")
    expect(kindFromMime("application/pdf")).toBe("document")
    expect(kindFromMime(undefined)).toBe("document")
  })
})
