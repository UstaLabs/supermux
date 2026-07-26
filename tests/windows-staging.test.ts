import { expect, test } from "bun:test"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, resolve } from "node:path"

const root = resolve(import.meta.dir, "..")
const destination = join(root, "apps", "desktop", "resources", "windows-x64")
const stagedNames = [
  "supermux-broker.exe",
  "mux-sessiond.exe",
  "frpc.exe",
  "tmux",
  "pty-helper.exe",
] as const

test("Windows staging contains the complete native host runtime and no POSIX helpers", async () => {
  const fixtureDir = mkdtempSync(join(tmpdir(), "supermux-windows-staging-"))
  const fixtures = {
    broker: join(fixtureDir, "broker.fixture"),
    sessiond: join(fixtureDir, "sessiond.fixture"),
    frpc: join(fixtureDir, "frpc.fixture"),
  }
  writeFileSync(fixtures.broker, "BROKER")
  writeFileSync(fixtures.sessiond, "SESSIOND")
  writeFileSync(fixtures.frpc, "FRPC")

  const previous = new Map<string, Buffer | null>()
  for (const name of stagedNames) {
    const path = join(destination, name)
    previous.set(path, existsSync(path) ? readFileSync(path) : null)
  }

  try {
    const proc = Bun.spawn(
      ["sh", join(root, "scripts", "stage-desktop-binaries.sh"), "windows-x64", "test", "deadbeef"],
      {
        cwd: root,
        env: {
          ...process.env,
          SUPERMUX_BROKER: fixtures.broker,
          SUPERMUX_SESSIOND: fixtures.sessiond,
          SUPERMUX_FRPC: fixtures.frpc,
          SUPERMUX_SKIP_BROKER: "1",
        },
        stdout: "pipe",
        stderr: "pipe",
      },
    )
    const [exitCode, stdout, stderr] = await Promise.all([
      proc.exited,
      new Response(proc.stdout).text(),
      new Response(proc.stderr).text(),
    ])
    expect(`${stdout}\n${stderr}`).toContain("[stage] target=windows-x64")
    expect(exitCode).toBe(0)

    expect(readFileSync(join(destination, "supermux-broker.exe"), "utf8")).toBe("BROKER")
    expect(readFileSync(join(destination, "mux-sessiond.exe"), "utf8")).toBe("SESSIOND")
    expect(readFileSync(join(destination, "frpc.exe"), "utf8")).toBe("FRPC")
    expect(existsSync(join(destination, "tmux"))).toBe(false)
    expect(existsSync(join(destination, "pty-helper.exe"))).toBe(false)
  } finally {
    for (const [path, contents] of previous) {
      if (contents === null) rmSync(path, { force: true })
      else writeFileSync(path, contents)
    }
    rmSync(fixtureDir, { recursive: true, force: true })
  }
})

test("sessiond compilation honors an explicit Windows x64 target", async () => {
  const fixtureDir = mkdtempSync(join(tmpdir(), "supermux-sessiond-build-"))
  const fakeBin = join(fixtureDir, "bin")
  const capture = join(fixtureDir, "bun-args.txt")
  const output = join(fixtureDir, "mux-sessiond.exe")
  mkdirSync(fakeBin)
  writeFileSync(
    join(fakeBin, "bun"),
    "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$SUPERMUX_CAPTURE\"\n",
  )
  chmodSync(join(fakeBin, "bun"), 0o755)

  try {
    const proc = Bun.spawn(
      ["sh", join(root, "scripts", "build-sessiond.sh"), output],
      {
        cwd: root,
        env: {
          ...process.env,
          PATH: `${fakeBin}:${process.env.PATH ?? ""}`,
          SUPERMUX_CAPTURE: capture,
          SUPERMUX_TARGET: "windows-x64",
        },
        stdout: "pipe",
        stderr: "pipe",
      },
    )
    expect(await proc.exited).toBe(0)
    expect(readFileSync(capture, "utf8").split(/\r?\n/)).toContain("--target=bun-windows-x64")
  } finally {
    rmSync(fixtureDir, { recursive: true, force: true })
  }
})
