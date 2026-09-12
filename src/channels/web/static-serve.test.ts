import { describe, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { serveStatic } from "./static-serve"

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
