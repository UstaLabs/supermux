import { describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, statSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { materializeAsset } from "./runtime-assets"

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
