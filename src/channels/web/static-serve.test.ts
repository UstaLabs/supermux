import { describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync } from "fs"
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
})
