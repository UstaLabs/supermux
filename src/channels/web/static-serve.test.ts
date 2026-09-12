import { describe, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, utimesSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { serveStatic, _gzipCacheStats } from "./static-serve"

function tmp(): string { return mkdtempSync(join(tmpdir(), "mux-static-")) }

describe("serveStatic (dual-mode)", () => {
  test("disk hit wins (today's behavior)", async () => {
    const dir = tmp()
    writeFileSync(join(dir, "index.html"), "<html>disk</html>")
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/" })
    expect(res).not.toBeNull()
    expect(await res!.text()).toBe("<html>disk</html>")
    expect(res!.headers.get("content-type")).toContain("text/html")
  })

  test("embedded fallback when disk misses", async () => {
    const dir = tmp() // empty — no index.html on disk
    const embDir = tmp()
    writeFileSync(join(embDir, "emb-index.html"), "<html>embedded</html>")
    const res = serveStatic({ staticDir: dir, embedded: { "/index.html": join(embDir, "emb-index.html") }, path: "/" })
    expect(res).not.toBeNull()
    expect(await res!.text()).toBe("<html>embedded</html>")
  })

  test("SPA fallback serves index for unknown paths, embedded mode included", async () => {
    const embDir = tmp()
    writeFileSync(join(embDir, "emb-index.html"), "<html>spa</html>")
    const res = serveStatic({ staticDir: "/nonexistent", embedded: { "/index.html": join(embDir, "emb-index.html") }, path: "/devices" })
    expect(await res!.text()).toBe("<html>spa</html>")
    expect(res!.headers.get("cache-control")).toContain("no-cache")
  })

  test("null when neither source has the file or an index", () => {
    expect(serveStatic({ staticDir: "/nonexistent", embedded: {}, path: "/nope.js" })).toBeNull()
  })

  test("dot-segment paths are rejected outright (defense in depth)", () => {
    const dir = tmp()
    writeFileSync(join(dir, "index.html"), "<html>x</html>")
    expect(serveStatic({ staticDir: dir, embedded: {}, path: "/../secret.txt" })).toBeNull()
    expect(serveStatic({ staticDir: dir, embedded: {}, path: "/a/../../b" })).toBeNull()
  })

  test("wasm assets get application/wasm, immutable caching and gzip", async () => {
    const dir = tmp()
    const { mkdirSync } = await import("fs")
    mkdirSync(join(dir, "assets"))
    writeFileSync(join(dir, "assets", "app-0123abcd.wasm"), Buffer.alloc(4096, 0))
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/assets/app-0123abcd.wasm", acceptEncoding: "gzip, br" })
    expect(res).not.toBeNull()
    expect(res!.headers.get("content-type")).toBe("application/wasm")
    expect(res!.headers.get("cache-control")).toContain("immutable")
    expect(res!.headers.get("content-encoding")).toBe("gzip")
  })

})

describe("serveStatic security headers", () => {
  // The editor iframe's bridge evals what its parent posts it. `frame-ancestors 'self'` is the
  // outer half of that guard (the shim's own origin check is the inner half): a foreign page must
  // not be able to frame this origin and become that parent in the first place.
  test("html disk hit carries frame-ancestors 'self'", () => {
    const dir = tmp()
    writeFileSync(join(dir, "index.html"), "<html>disk</html>")
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/" })
    expect(res!.headers.get("content-security-policy")).toBe("frame-ancestors 'self'")
  })

  test("the editor page carries it too", () => {
    const dir = tmp()
    mkdirSync(join(dir, "editor"))
    writeFileSync(join(dir, "editor", "index.html"), "<html>cm6</html>")
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/editor/index.html" })
    expect(res!.headers.get("content-security-policy")).toBe("frame-ancestors 'self'")
  })

  test("the SPA fallback carries it (the path a framer would actually request)", () => {
    const embDir = tmp()
    writeFileSync(join(embDir, "emb-index.html"), "<html>spa</html>")
    const res = serveStatic({ staticDir: "/nonexistent", embedded: { "/index.html": join(embDir, "emb-index.html") }, path: "/s/whatever" })
    expect(res!.headers.get("content-security-policy")).toBe("frame-ancestors 'self'")
  })
})

describe("serveStatic MIME types for the wasm bundle's composeResources fonts/text assets", () => {
  test(".ttf -> font/ttf, gzip on request", () => {
    const dir = tmp()
    writeFileSync(join(dir, "font.ttf"), Buffer.alloc(2048, 0x41))
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/font.ttf", acceptEncoding: "gzip" })
    expect(res!.headers.get("content-type")).toBe("font/ttf")
    expect(res!.headers.get("content-encoding")).toBe("gzip")
  })

  test(".otf -> font/otf", () => {
    const dir = tmp()
    writeFileSync(join(dir, "font.otf"), "x")
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/font.otf" })
    expect(res!.headers.get("content-type")).toBe("font/otf")
  })

  test(".woff -> font/woff", () => {
    const dir = tmp()
    writeFileSync(join(dir, "font.woff"), "x")
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/font.woff" })
    expect(res!.headers.get("content-type")).toBe("font/woff")
  })

  test(".xml -> application/xml, gzip on request", () => {
    const dir = tmp()
    writeFileSync(join(dir, "manifest.xml"), Buffer.alloc(2048, 0x42))
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/manifest.xml", acceptEncoding: "gzip" })
    expect(res!.headers.get("content-type")).toBe("application/xml")
    expect(res!.headers.get("content-encoding")).toBe("gzip")
  })

  test(".txt -> text/plain; charset=utf-8, gzip on request", () => {
    const dir = tmp()
    writeFileSync(join(dir, "notes.txt"), Buffer.alloc(2048, 0x43))
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/notes.txt", acceptEncoding: "gzip" })
    expect(res!.headers.get("content-type")).toBe("text/plain; charset=utf-8")
    expect(res!.headers.get("content-encoding")).toBe("gzip")
  })
})

describe("serveStatic /editor/ gzip cache", () => {
  test("second request for /editor/cm6.js is served from the gzip cache", () => {
    const dir = tmp()
    mkdirSync(join(dir, "editor"))
    // Large, compressible, repetitive content so gzip clears the 15% savings bar.
    writeFileSync(join(dir, "editor", "cm6.js"), "x".repeat(200_000))
    const before = _gzipCacheStats()
    const res1 = serveStatic({ staticDir: dir, embedded: {}, path: "/editor/cm6.js", acceptEncoding: "gzip" })
    expect(res1!.headers.get("content-encoding")).toBe("gzip")
    const afterFirst = _gzipCacheStats()
    expect(afterFirst.size).toBe(before.size + 1)

    const res2 = serveStatic({ staticDir: dir, embedded: {}, path: "/editor/cm6.js", acceptEncoding: "gzip" })
    expect(res2!.headers.get("content-encoding")).toBe("gzip")
    const afterSecond = _gzipCacheStats()
    expect(afterSecond.size).toBe(afterFirst.size) // no new entry
    expect(afterSecond.hits).toBe(before.hits + 1) // served from cache
  })

  test("the cache invalidates when the file's mtime changes, replacing the entry in place", async () => {
    const dir = tmp()
    mkdirSync(join(dir, "editor"))
    const filePath = join(dir, "editor", "cm6.js")
    writeFileSync(filePath, "y".repeat(200_000))
    serveStatic({ staticDir: dir, embedded: {}, path: "/editor/cm6.js", acceptEncoding: "gzip" })
    const afterFirst = _gzipCacheStats()

    // Bump mtime forward so it's guaranteed to differ, then rewrite the content.
    const future = new Date(Date.now() + 60_000)
    writeFileSync(filePath, "z".repeat(200_000))
    utimesSync(filePath, future, future)

    const res2 = serveStatic({ staticDir: dir, embedded: {}, path: "/editor/cm6.js", acceptEncoding: "gzip" })
    const afterSecond = _gzipCacheStats()
    // The stale entry was overwritten in place, not orphaned under a new key.
    expect(afterSecond.size).toBe(afterFirst.size)
    expect(res2!.headers.get("content-encoding")).toBe("gzip")
    // The bytes served are the new content, not the stale cached ones.
    const { gunzipSync } = await import("zlib")
    const decompressed = gunzipSync(Buffer.from(await res2!.arrayBuffer())).toString()
    expect(decompressed).toBe("z".repeat(200_000))
  })
})
