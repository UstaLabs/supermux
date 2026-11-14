// #142: scripts/precompress-static.ts writes a brotli sibling next to every compressible staged asset,
// once, so the broker never spends a minute of CPU compressing the 22 MB app wasm per request.
import { describe, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, statSync, utimesSync, writeFileSync } from "fs"
import { brotliDecompressSync } from "zlib"
import { join } from "path"
import { tmpdir } from "os"
import { precompressStatic } from "../scripts/precompress-static"

function staged(): string {
  const root = mkdtempSync(join(tmpdir(), "mux-precompress-"))
  mkdirSync(join(root, "assets", "composeResources", "pkg", "font"), { recursive: true })
  writeFileSync(join(root, "index.html"), "<html>" + "x".repeat(4000) + "</html>")
  writeFileSync(join(root, "assets", "app-0123abcd.wasm"), Buffer.from("wasm ".repeat(4000)))
  writeFileSync(join(root, "assets", "app-0123abcd.js"), "console.log(1);".repeat(400))
  writeFileSync(join(root, "assets", "composeResources", "pkg", "font", "mono.ttf"), Buffer.from("glyph".repeat(2000)))
  writeFileSync(join(root, "assets", "icon.png"), Buffer.alloc(4096, 1)) // not compressible by type
  writeFileSync(join(root, "assets", "tiny-0123abcd.mjs"), "export {}") // under the size floor
  return root
}

describe("precompressStatic", () => {
  test("writes a .br that decodes to the original, for compressible assets only", async () => {
    const root = staged()
    const r = await precompressStatic(root, { quality: 5 })
    for (const rel of ["assets/app-0123abcd.wasm", "assets/app-0123abcd.js", "assets/composeResources/pkg/font/mono.ttf"]) {
      const br = join(root, rel + ".br")
      expect(existsSync(br)).toBe(true)
      expect(brotliDecompressSync(readFileSync(br)).equals(readFileSync(join(root, rel)))).toBe(true)
    }
    expect(existsSync(join(root, "assets", "icon.png.br"))).toBe(false)
    expect(existsSync(join(root, "assets", "tiny-0123abcd.mjs.br"))).toBe(false)
    // index.html and sw.js are entry points that change on a live deploy without a rename; they stay runtime-gzipped.
    expect(existsSync(join(root, "index.html.br"))).toBe(false)
    expect(r.written).toBe(3)
  })

  test("a second run rewrites nothing; a newer original is recompressed", async () => {
    const root = staged()
    await precompressStatic(root, { quality: 5 })
    expect((await precompressStatic(root, { quality: 5 })).written).toBe(0)
    const wasm = join(root, "assets", "app-0123abcd.wasm")
    writeFileSync(wasm, Buffer.from("changed ".repeat(4000)))
    const future = new Date(Date.now() + 5_000)
    utimesSync(wasm, future, future)
    expect((await precompressStatic(root, { quality: 5 })).written).toBe(1)
    expect(brotliDecompressSync(readFileSync(wasm + ".br")).equals(readFileSync(wasm))).toBe(true)
    expect(statSync(wasm + ".br").mtimeMs).toBeGreaterThanOrEqual(statSync(wasm).mtimeMs)
  })

  test("an orphaned .br (its original is gone) is removed", async () => {
    const root = staged()
    writeFileSync(join(root, "assets", "old-deadbeef.wasm.br"), "stale")
    const r = await precompressStatic(root, { quality: 5 })
    expect(existsSync(join(root, "assets", "old-deadbeef.wasm.br"))).toBe(false)
    expect(r.removed).toBe(1)
  })
})
