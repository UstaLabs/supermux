import { describe, expect, test } from "bun:test"
import { createHash } from "crypto"
import {
  GROK_MIN_BYTES, GROK_STABLE_URL, OPENCODE_LATEST_RELEASE, grokWindowsDir, installGrokWindows, installOpenCodeWindows,
  openCodeWindowsDir, type BuiltinInstallDeps,
} from "./install-builtin"

const ENV = { USERPROFILE: "C:\\Users\\t", LOCALAPPDATA: "C:\\Users\\t\\AppData\\Local" }
const sha = (b: Uint8Array) => createHash("sha256").update(b).digest("hex")

/** An in-memory Windows (files are keys, dirs are prefixes) plus a scripted network. */
function fakeWindows(routes: Record<string, () => Response>, opts: { userPath?: string | null; zipHasExe?: boolean; arch?: string } = {}) {
  const files = new Map<string, Uint8Array>()
  const fetched: string[] = []
  const writes: string[] = []
  const lines: string[] = []
  let userPath: string | null = opts.userPath === undefined ? "C:\\Tools" : opts.userPath
  const has = (p: string) => [...files.keys()].some((f) => f === p || f.startsWith(p + "\\"))
  const deps: BuiltinInstallDeps = {
    env: ENV,
    arch: opts.arch ?? "x64",
    fetch: (async (url: string) => {
      fetched.push(url)
      const r = routes[url]
      return r ? r() : new Response("not found", { status: 404 })
    }) as unknown as typeof fetch,
    sha256: sha,
    extract: async (_zip, dest) => {
      if (opts.zipHasExe !== false) files.set(`${dest}\\opencode.exe`, new Uint8Array([1]))
    },
    fs: {
      exists: has,
      mkdir: () => {},
      write: (p, b) => { files.set(p, b) },
      rename: (from, to) => {
        for (const [f, b] of [...files]) {
          if (f === from || f.startsWith(from + "\\")) { files.delete(f); files.set(to + f.slice(from.length), b) }
        }
      },
      remove: (p) => { for (const f of [...files.keys()]) if (f === p || f.startsWith(p + "\\")) files.delete(f) },
    },
    readUserPath: async () => userPath,
    writeUserPath: async (v) => { writes.push(v); userPath = v },
    log: (l) => lines.push(l),
    now: () => 42,
    pid: 7,
  }
  return { deps, files, fetched, writes, lines }
}

const ZIP = new Uint8Array([0x50, 0x4b, 3, 4, 9, 9])
const ZIP_URL = "https://github.com/anomalyco/opencode/releases/download/v1.18.34/opencode-windows-x64.zip"
function release(digest: string | null, name = "opencode-windows-x64.zip") {
  return () => Response.json({
    tag_name: "v1.18.34",
    assets: [
      { name: "opencode-windows-arm64.zip", browser_download_url: "https://example.invalid/arm64.zip", digest: `sha256:${"0".repeat(64)}` },
      { name, browser_download_url: ZIP_URL, digest },
    ],
  })
}

describe("OpenCode on Windows", () => {
  test("happy path: digest verified, unpacked to Programs\\opencode, dir on the user PATH once", async () => {
    const w = fakeWindows({ [OPENCODE_LATEST_RELEASE]: release(`sha256:${sha(ZIP)}`), [ZIP_URL]: () => new Response(ZIP) })
    await installOpenCodeWindows(w.deps)
    const root = openCodeWindowsDir(ENV)
    expect(root).toBe("C:\\Users\\t\\AppData\\Local\\Programs\\opencode")
    expect(w.files.has(`${root}\\opencode.exe`)).toBe(true)
    expect([...w.files.keys()].some((f) => f.includes(".tmp-") || f.endsWith(".zip"))).toBe(false)
    expect(w.fetched).toEqual([OPENCODE_LATEST_RELEASE, ZIP_URL])
    expect(w.writes).toEqual([`C:\\Tools;${root}`])
    expect(w.lines.join("\n")).toContain("SHA-256 verified")
  })

  test("a digest mismatch refuses: nothing unpacked, PATH untouched", async () => {
    const w = fakeWindows({ [OPENCODE_LATEST_RELEASE]: release(`sha256:${"a".repeat(64)}`), [ZIP_URL]: () => new Response(ZIP) })
    const err = await installOpenCodeWindows(w.deps).catch((e) => e)
    expect(String(err)).toContain("checksum mismatch")
    expect(w.files.size).toBe(0)
    expect(w.writes).toEqual([])
  })

  test("no digest published: refused before downloading", async () => {
    const w = fakeWindows({ [OPENCODE_LATEST_RELEASE]: release(null), [ZIP_URL]: () => new Response(ZIP) })
    const err = await installOpenCodeWindows(w.deps).catch((e) => e)
    expect(String(err)).toContain("no SHA-256 digest")
    expect(w.fetched).toEqual([OPENCODE_LATEST_RELEASE])
  })

  test("an archive without opencode.exe is refused and leaves nothing behind", async () => {
    const w = fakeWindows({ [OPENCODE_LATEST_RELEASE]: release(`sha256:${sha(ZIP)}`), [ZIP_URL]: () => new Response(ZIP) }, { zipHasExe: false })
    const err = await installOpenCodeWindows(w.deps).catch((e) => e)
    expect(String(err)).toContain("no opencode.exe")
    expect(w.files.size).toBe(0)
  })

  test("arm64 picks the arm64 asset", async () => {
    const w = fakeWindows({ [OPENCODE_LATEST_RELEASE]: release(`sha256:${sha(ZIP)}`) }, { arch: "arm64" })
    await installOpenCodeWindows(w.deps).catch(() => {})
    expect(w.fetched[1]).toBe("https://example.invalid/arm64.zip")
  })

  test("the user PATH is not duplicated when the dir is already there (any case, trailing slash)", async () => {
    const root = openCodeWindowsDir(ENV)
    const w = fakeWindows(
      { [OPENCODE_LATEST_RELEASE]: release(`sha256:${sha(ZIP)}`), [ZIP_URL]: () => new Response(ZIP) },
      { userPath: `C:\\Tools;${root.toUpperCase()}\\` },
    )
    await installOpenCodeWindows(w.deps)
    expect(w.writes).toEqual([])
    expect(w.lines.join("\n")).toContain("already on your PATH")
  })
})

function exe(size: number, header = [0x4d, 0x5a]): Uint8Array {
  const b = new Uint8Array(size)
  b.set(header)
  return b
}
const GROK_URL = "https://x.ai/cli/grok-1.0.46-windows-x86_64.exe"

describe("Grok on Windows", () => {
  test("happy path: version from stable, exe to ~/.grok/bin, PATH once, the log says there's no checksum", async () => {
    const w = fakeWindows({ [GROK_STABLE_URL]: () => new Response("1.0.46\n"), [GROK_URL]: () => new Response(exe(GROK_MIN_BYTES + 1)) })
    await installGrokWindows(w.deps)
    const dir = grokWindowsDir(ENV)
    expect(dir).toBe("C:\\Users\\t\\.grok\\bin")
    expect(w.files.get(`${dir}\\grok.exe`)?.length).toBe(GROK_MIN_BYTES + 1)
    expect([...w.files.keys()].some((f) => f.includes(".tmp-"))).toBe(false)
    expect(w.writes).toEqual([`C:\\Tools;${dir}`])
    expect(w.lines.join("\n")).toContain("publishes no checksum")
  })

  test("arm64 downloads the aarch64 build", async () => {
    const w = fakeWindows({ [GROK_STABLE_URL]: () => new Response("1.0.46") }, { arch: "arm64" })
    await installGrokWindows(w.deps).catch(() => {})
    expect(w.fetched[1]).toBe("https://x.ai/cli/grok-1.0.46-windows-aarch64.exe")
  })

  test("a download under 1 MB is refused", async () => {
    const w = fakeWindows({ [GROK_STABLE_URL]: () => new Response("1.0.46"), [GROK_URL]: () => new Response(exe(4096)) })
    const err = await installGrokWindows(w.deps).catch((e) => e)
    expect(String(err)).toContain("only 4096 bytes")
    expect(w.files.size).toBe(0)
    expect(w.writes).toEqual([])
  })

  test("a download without the MZ header is refused", async () => {
    const w = fakeWindows({ [GROK_STABLE_URL]: () => new Response("1.0.46"), [GROK_URL]: () => new Response(exe(GROK_MIN_BYTES + 1, [0x3c, 0x68])) })
    const err = await installGrokWindows(w.deps).catch((e) => e)
    expect(String(err)).toContain("MZ")
    expect(w.files.size).toBe(0)
  })

  test("a non-version answer from the stable channel is refused before any download", async () => {
    const w = fakeWindows({ [GROK_STABLE_URL]: () => new Response("<html>oops</html>") })
    const err = await installGrokWindows(w.deps).catch((e) => e)
    expect(String(err)).toContain("unexpected Grok version")
    expect(w.fetched).toEqual([GROK_STABLE_URL])
  })
})
