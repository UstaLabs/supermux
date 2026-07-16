import { describe, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { environmentMdContent, frpcPath, materializeAsset } from "./runtime-assets"
import { readEnvironmentMd } from "./agents/environment"

describe("materializeAsset", () => {
  test("copies the source file into the version-keyed dir and chmods it", () => {
    const stateDir = mkdtempSync(join(tmpdir(), "mux-ra-"))
    const src = join(stateDir, "src-file")
    writeFileSync(src, "#!/bin/sh\necho hi\n")
    const out = materializeAsset({ stateDir, name: "pty-helper", sourcePath: src, executable: true })
    expect(readFileSync(out, "utf8")).toBe("#!/bin/sh\necho hi\n")
    expect(out).toContain("/runtime-assets/")
    expect(statSync(out).mode & 0o111).toBeTruthy() // executable bits set
  })

  test("non-executable asset gets no exec bits", () => {
    const stateDir = mkdtempSync(join(tmpdir(), "mux-ra-"))
    const src = join(stateDir, "doc")
    writeFileSync(src, "just text")
    const out = materializeAsset({ stateDir, name: "doc.md", sourcePath: src })
    expect(statSync(out).mode & 0o111).toBe(0)
  })

  test("idempotent: second call returns same path without rewriting", () => {
    const stateDir = mkdtempSync(join(tmpdir(), "mux-ra-"))
    const src = join(stateDir, "f")
    writeFileSync(src, "v1")
    const first = materializeAsset({ stateDir, name: "f.md", sourcePath: src })
    const mtime1 = statSync(first).mtimeMs
    const second = materializeAsset({ stateDir, name: "f.md", sourcePath: src })
    expect(second).toBe(first)
    expect(statSync(second).mtimeMs).toBe(mtime1)
  })
})

describe("environment.md single-importer rule", () => {
  // Both consumers (the path helper's content sibling and the agents-side
  // readEnvironmentMd) are imported statically at the top of THIS file, so they
  // coexist in one process — the exact condition under which the old
  // dual-attribute import (file vs text) silently collapsed. If the bindings
  // ever diverge again, readEnvironmentMd would yield a path (or empty) and
  // these assertions break.
  test("path and content consumers coexist (single-importer rule)", () => {
    const content = readEnvironmentMd()
    expect(content.length).toBeGreaterThan(500)
    expect(content).toContain("supermux")
    expect(existsSync(content)).toBe(false) // content, not a path
    expect(environmentMdContent()).toBe(content) // both readers agree
  })
})

test("frpcPath honors an explicit helper path", () => {
  const previous = process.env.MUX_FRPC_PATH
  process.env.MUX_FRPC_PATH = "/opt/supermux/frpc"
  try {
    expect(frpcPath("/tmp/unused")).toBe("/opt/supermux/frpc")
  } finally {
    if (previous === undefined) delete process.env.MUX_FRPC_PATH
    else process.env.MUX_FRPC_PATH = previous
  }
})
