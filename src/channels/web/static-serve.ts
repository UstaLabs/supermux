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

function cacheControlFor(candidate: string): string {
  // Vite emits hashed filenames under /assets/ — those are content-addressed
  // (changing the bundle changes the URL) and safe to cache forever.
  if (candidate.startsWith("/assets/")) return "public, max-age=31536000, immutable"
  // Everything else is an entry point (HTML, sw.js, registerSW.js, manifest,
  // icons). Force revalidation so Cloudflare + browser PWAs pick up updates.
  return "no-cache, must-revalidate"
}

export function serveStatic(opts: { staticDir: string | undefined; embedded: Record<string, string>; path: string }): Response | null {
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
      return new Response(readFileSync(filePath), {
        headers: { "content-type": guessMime(filePath), "cache-control": cacheControlFor(candidate) },
      })
    }
  }

  const embeddedPath = opts.embedded[candidate]
  if (embeddedPath) {
    return new Response(Bun.file(embeddedPath), {
      headers: { "content-type": guessMime(candidate), "cache-control": cacheControlFor(candidate) },
    })
  }

  // SPA fallback (vue-router paths like /devices, /s/ana)
  if (opts.staticDir) {
    const idx = join(opts.staticDir, "index.html")
    if (existsSync(idx)) {
      return new Response(readFileSync(idx), { headers: { "content-type": "text/html", "cache-control": "no-cache, must-revalidate" } })
    }
  }
  const embIdx = opts.embedded["/index.html"]
  if (embIdx) {
    return new Response(Bun.file(embIdx), { headers: { "content-type": "text/html", "cache-control": "no-cache, must-revalidate" } })
  }
  return null
}
