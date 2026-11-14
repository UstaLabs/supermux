// scripts/precompress-static.ts
// Writes a brotli sibling (`x.wasm.br`) next to every compressible file under the staged web
// client's assets/ (#142), which src/channels/web/static-serve.ts serves to clients that accept br.
// Brotli at quality 11 is ~26% smaller than the gzip the broker makes on the fly (app wasm 5.8 →
// 4.0 MiB, Skia 3.3 → 2.6 MiB) but costs ~1.5 min of CPU on the app wasm, so it runs ONCE here,
// after `:web:stageForBroker`, never per request. Entry points outside assets/ (index.html, sw.js)
// change on a live deploy without a rename and stay runtime-gzipped.
//
// Run: bun scripts/precompress-static.ts [staticDir]   (default src/channels/web/static)
// PRECOMPRESS_QUALITY=5 trades ~10% size for seconds instead of minutes (test lanes only).
// Idempotent: an up-to-date .br is kept, a stale one rewritten, an orphan removed.
import { existsSync, readdirSync, readFileSync, statSync, unlinkSync, utimesSync, writeFileSync } from "fs"
import { join, resolve } from "path"
import { brotliCompress, constants } from "zlib"
import { promisify } from "util"

const brotli = promisify(brotliCompress)

// The same set static-serve.ts gzips, minus the entry-point types that never live under assets/.
const COMPRESSIBLE = /\.(js|mjs|css|json|svg|wasm|ttf|otf|xml|txt|html|sesz)$/
// Below this the header and the extra file are not worth it.
const MIN_BYTES = 1024

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.isFile()) out.push(p)
  }
  return out
}

export async function precompressStatic(root: string, opts: { quality?: number } = {}): Promise<{ written: number; removed: number; kept: number }> {
  const assets = join(root, "assets")
  if (!existsSync(assets)) return { written: 0, removed: 0, kept: 0 }
  const quality = opts.quality ?? 11
  const files = walk(assets)
  let removed = 0
  for (const f of files.filter((f) => f.endsWith(".br"))) {
    if (!existsSync(f.slice(0, -3))) { unlinkSync(f); removed++ }
  }
  let written = 0
  let kept = 0
  await Promise.all(files.filter((f) => !f.endsWith(".br") && COMPRESSIBLE.test(f)).map(async (f) => {
    const stat = statSync(f)
    if (stat.size < MIN_BYTES) return
    const br = f + ".br"
    if (existsSync(br) && statSync(br).mtimeMs >= stat.mtimeMs) { kept++; return }
    const raw = readFileSync(f)
    const out = await brotli(raw, {
      params: {
        [constants.BROTLI_PARAM_QUALITY]: quality,
        [constants.BROTLI_PARAM_LGWIN]: 24,
        [constants.BROTLI_PARAM_SIZE_HINT]: raw.length,
      },
    })
    // Not smaller by a margin worth a second file: let the runtime gzip path handle it.
    if (out.length >= raw.length * 0.9) { if (existsSync(br)) { unlinkSync(br); removed++ }; return }
    writeFileSync(br, out)
    // Never older than its original, whatever the clocks say: static-serve treats an older .br as stale.
    if (statSync(br).mtimeMs < stat.mtimeMs) utimesSync(br, stat.atime, stat.mtime)
    written++
  }))
  return { written, removed, kept }
}

if (import.meta.main) {
  const root = resolve(process.argv[2] ?? join(import.meta.dirname, "..", "src/channels/web/static"))
  if (!existsSync(join(root, "index.html"))) {
    console.error(`precompress-static: ${root}/index.html missing — stage the web client first (cd apps && ./gradlew :web:stageForBroker)`)
    process.exit(1)
  }
  const started = Date.now()
  const q = process.env.PRECOMPRESS_QUALITY ? Number(process.env.PRECOMPRESS_QUALITY) : undefined
  const r = await precompressStatic(root, { quality: q })
  console.log(`precompress-static: ${r.written} written, ${r.kept} up to date, ${r.removed} removed in ${((Date.now() - started) / 1000).toFixed(1)}s → ${root}`)
}
