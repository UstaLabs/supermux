import { test, expect } from "bun:test"
import { dartExecutable, dartSdkZipUrl, extractZipArgv } from "./install-dart-sdk"

test("dartSdkZipUrl uses current stable archive layout", () => {
  expect(dartSdkZipUrl("x64", "linux")).toBe(
    "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/dartsdk-linux-x64-release.zip",
  )
  expect(dartSdkZipUrl("arm64", "linux")).toContain("dartsdk-linux-arm64-release.zip")
})

// The broker downloaded the Linux SDK on every OS: on a Mac its dart could not run, so the Dart language server never
// started. Each OS gets its own build, and Windows its dart.exe.
test("each OS gets its own Dart SDK build, and Windows its dart.exe", () => {
  expect(dartSdkZipUrl("arm64", "darwin")).toContain("dartsdk-macos-arm64-release.zip")
  expect(dartSdkZipUrl("x64", "win32")).toContain("dartsdk-windows-x64-release.zip")
  expect(dartExecutable("win32")).toBe("dart.exe")
  expect(dartExecutable("darwin")).toBe("dart")
})

// Windows has no unzip; its tar (bsdtar, in Windows 10 and later) reads zip archives.
test("the archive is unpacked with unzip, or with tar on Windows", () => {
  expect(extractZipArgv("/x/sdk.zip", "/x", "linux")).toEqual(["unzip", "-qo", "/x/sdk.zip", "-d", "/x"])
  expect(extractZipArgv("C:\\x\\sdk.zip", "C:\\x", "win32")).toEqual(["tar", "-xf", "C:\\x\\sdk.zip", "-C", "C:\\x"])
})
