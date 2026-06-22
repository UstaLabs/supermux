import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, chmodSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join, delimiter } from "path"
import { hasBinary } from "./preflight"

const origPath = process.env.PATH
afterEach(() => {
  process.env.PATH = origPath
})

// Regression: an agent installed at runtime (via the settings install button)
// lands in a dir we prepend to process.env.PATH. Bun's execSync IGNORES an
// in-process PATH mutation unless env is passed explicitly, so hasBinary must
// pass env — otherwise a freshly-installed agent reads as not-installed.
test("hasBinary sees a binary added to process.env.PATH at runtime", () => {
  const dir = mkdtempSync(join(tmpdir(), "preflight-bin-"))
  try {
    const bin = join(dir, "mux-fake-tool")
    writeFileSync(bin, "#!/bin/sh\necho ok\n")
    chmodSync(bin, 0o755)

    expect(hasBinary("mux-fake-tool")).toBe(false) // not on PATH yet
    process.env.PATH = `${dir}${delimiter}${origPath ?? ""}`
    expect(hasBinary("mux-fake-tool")).toBe(true) // now resolvable via the mutated PATH
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
