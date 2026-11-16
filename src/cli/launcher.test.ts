import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { installSupermuxLauncher } from "./launcher"

const bin = () => join(mkdtempSync(join(tmpdir(), "sm-launch-")), "bin")

test("source install: the launcher runs src/cli.ts of the broker's checkout with the broker's bun", () => {
  const p = installSupermuxLauncher(bin(), "/repo", { execPath: "/u/bun", compiled: false, platform: "linux" })
  expect(readFileSync(p, "utf8")).toBe('#!/bin/sh\nexec "/u/bun" "/repo/src/cli.ts" "$@"\n')
  // The executable bit only exists on a POSIX host (the Windows lane writes the same POSIX text, without the bit).
  if (process.platform !== "win32") expect(statSync(p).mode & 0o100).toBeTruthy()
})

test("compiled install: the launcher runs the broker's own binary", () => {
  const p = installSupermuxLauncher(bin(), "/repo", { execPath: "/opt/supermux/supermux", compiled: true, platform: "darwin" })
  expect(readFileSync(p, "utf8")).toBe('#!/bin/sh\nexec "/opt/supermux/supermux" "$@"\n')
})

test("Windows gets supermux.cmd, the checkout in Windows spelling", () => {
  const src = installSupermuxLauncher(bin(), "C:\\repo", { execPath: "C:\\bun.exe", compiled: false, platform: "win32" })
  expect(readFileSync(src, "utf8")).toBe('@"C:\\bun.exe" "C:\\repo\\src\\cli.ts" %*\r\n')
  const p = installSupermuxLauncher(bin(), "C:\\repo", { execPath: "C:\\sm\\supermux.exe", compiled: true, platform: "win32" })
  expect(p.endsWith("supermux.cmd")).toBe(true)
  expect(readFileSync(p, "utf8")).toBe('@"C:\\sm\\supermux.exe" %*\r\n')
})
