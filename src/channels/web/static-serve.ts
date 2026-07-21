// src/channels/web/static-serve.ts
// Static-file resolution, extracted from the request handler so it has one
// owner and is testable. DISK FIRST (per-request read — a rebuilt PWA is
// picked up without restarting the broker, which live deploys rely on), then
// the embedded map (compiled binaries, where staticDir doesn't exist), then
// SPA-fallback to whichever index.html is available. Returns null if nothing
// matches (caller continues to its 404/handler chain).
import { existsSync, readFileSync, statSync } from "fs"
import { join } from "path"

function guessMime(p: string): string {
  if (p.endsWith(".html")) return "text/html"
  if (p.endsWith(".js"))   return "application/javascript"
  if (p.endsWith(".css"))  return "text/css"
  if (p.endsWith(".json")) return "application/json"
  if (p.endsWith(".svg"))  return "image/svg+xml"
  if (p.endsWith(".png"))  return "image/png"
  if (p.endsWith(".webmanifest")) return "application/manifest+json"
  if (p.endsWith(".ico"))  return "image/x-icon"
  if (p.endsWith(".woff2")) return "font/woff2"
  return "application/octet-stream"
}

const COMPRESSIBLE = /\.(html|js|css|json|svg|webmanifest)$/
const gzipCache = new Map<string, { body: Buffer; mtime: number }>()

function maybeGzip(candidate: string, body: Buffer, acceptEncoding: string | undefined): { body: Buffer | Uint8Array; encoding?: string } {
  if (!acceptEncoding?.includes("gzip") || !COMPRESSIBLE.test(candidate)) return { body }
  // Only cache content-addressed /assets/ files (hashed filenames change with
  // content). Entry points (index.html, sw.js) are small and may change on
  // live-deploy, so always re-compress them.
  const cacheable = candidate.startsWith("/assets/")
  if (cacheable) {
    const cached = gzipCache.get(candidate)
    if (cached) return { body: cached.body, encoding: "gzip" }
  }
  const compressed = Bun.gzipSync(new Uint8Array(body.buffer as ArrayBuffer, body.byteOffset, body.byteLength))
  if (compressed.byteLength < body.byteLength * 0.85) {
    if (cacheable) gzipCache.set(candidate, { body: Buffer.from(compressed), mtime: Date.now() })
    return { body: compressed, encoding: "gzip" }
  }
  return { body }
}

function cacheControlFor(candidate: string): string {
  // Vite emits hashed filenames under /assets/ — those are content-addressed
  // (changing the bundle changes the URL) and safe to cache forever.
  if (candidate.startsWith("/assets/")) return "public, max-age=31536000, immutable"
  // Everything else is an entry point (HTML, sw.js, registerSW.js, manifest,
  // icons). Force revalidation so Cloudflare + browser PWAs pick up updates.
  return "no-cache, must-revalidate"
}

export function serveStatic(opts: { staticDir: string | undefined; embedded: Record<string, string>; path: string; acceptEncoding?: string }): Response | null {
  const candidate = opts.path === "/" ? "/index.html" : opts.path

  // Defensive: callers pass normalized URL pathnames (Bun's HTTP layer +
  // WHATWG URL collapse dot-segments twice), but this function is exported —
  // a future caller handing it a raw string must not be able to escape
  // staticDir. Reject any dot-segment outright; no legitimate PWA asset
  // path contains "..".
  if (candidate.includes("..")) return null

  if (opts.staticDir) {
    const filePath = join(opts.staticDir, candidate)
    if (existsSync(filePath) && statSync(filePath).isFile()) {
      const raw = readFileSync(filePath)
      const { body, encoding } = maybeGzip(candidate, raw, opts.acceptEncoding)
      const headers: Record<string, string> = { "content-type": guessMime(filePath), "cache-control": cacheControlFor(candidate) }
      if (encoding) headers["content-encoding"] = encoding
      return new Response(body, { headers })
    }
  }

  const embeddedPath = opts.embedded[candidate]
  if (embeddedPath) {
    const raw = readFileSync(embeddedPath)
    const { body, encoding } = maybeGzip(candidate, raw, opts.acceptEncoding)
    const headers: Record<string, string> = { "content-type": guessMime(candidate), "cache-control": cacheControlFor(candidate) }
    if (encoding) headers["content-encoding"] = encoding
    return new Response(body, { headers })
  }

  // SPA fallback (vue-router paths like /devices, /s/ana)
  if (opts.staticDir) {
    const idx = join(opts.staticDir, "index.html")
    if (existsSync(idx)) {
      const raw = readFileSync(idx)
      const { body, encoding } = maybeGzip("/index.html", raw, opts.acceptEncoding)
      const headers: Record<string, string> = { "content-type": "text/html", "cache-control": "no-cache, must-revalidate" }
      if (encoding) headers["content-encoding"] = encoding
      return new Response(body, { headers })
    }
  }
  const embIdx = opts.embedded["/index.html"]
  if (embIdx) {
    const raw = readFileSync(embIdx)
    const { body, encoding } = maybeGzip("/index.html", raw, opts.acceptEncoding)
    const headers: Record<string, string> = { "content-type": "text/html", "cache-control": "no-cache, must-revalidate" }
    if (encoding) headers["content-encoding"] = encoding
    return new Response(body, { headers })
  }
  return null
}
