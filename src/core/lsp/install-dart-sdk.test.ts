import { test, expect } from "bun:test"
import { dartSdkZipUrl } from "./install-dart-sdk"

test("dartSdkZipUrl uses current stable archive layout", () => {
  expect(dartSdkZipUrl("x64")).toBe(
    "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/dartsdk-linux-x64-release.zip",
  )
  expect(dartSdkZipUrl("arm64")).toContain("dartsdk-linux-arm64-release.zip")
})
