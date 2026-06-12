import { describe, expect, test } from "bun:test"

function runCli(args: string[]): { code: number; stdout: string; stderr: string } {
  const r = Bun.spawnSync(["bun", "src/cli.ts", ...args], { cwd: import.meta.dirname + "/.." })
  return { code: r.exitCode, stdout: r.stdout.toString(), stderr: r.stderr.toString() }
}

describe("cli dispatcher", () => {
  test("version prints versionString and exits 0", () => {
    const r = runCli(["version"])
    expect(r.code).toBe(0)
    expect(r.stdout.trim()).toBe("dev (unknown)")
  })

  test("unknown subcommand exits 2 with usage on stderr", () => {
    const r = runCli(["frobnicate"])
    expect(r.code).toBe(2)
    expect(r.stderr).toContain("usage: supermux")
    expect(r.stderr).toContain("frobnicate")
  })

  test("pair without a name exits 1 with the script's own usage", () => {
    // proves argv shifting: scripts/pair.ts reads argv[2] as the device name
    const r = runCli(["pair"])
    expect(r.code).toBe(1)
    expect(r.stderr).toContain("usage: bun run pair <device-name>")
  })
})
