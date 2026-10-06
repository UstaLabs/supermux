import { describe, expect, test } from "bun:test"
import { createHash } from "crypto"
import {
  CURSOR_INSTALL_SCRIPT, OPENCODE_LATEST_RELEASE_API, cursorVersionFromScript, installCursorLinux, installOpenCodeLinux,
  openCodeLinuxAsset, type LinuxBuiltinDeps,
} from "./install-builtin-linux"

const sha = (b: Uint8Array) => createHash("sha256").update(b).digest("hex")

/** An in-memory Linux home plus a scripted network. Directories are prefixes of file keys. */
function fakeLinux(routes: Record<string, () => Response>, opts: { unpack?: (dest: string, strip: number) => string[]; avx2?: boolean; musl?: boolean; arch?: string } = {}) {
  const files = new Map<string, Uint8Array>()
  const links = new Map<string, string>()
  const modes = new Map<string, number>()
  const fetched: string[] = []
  const lines: string[] = []
  const untars: Array<[string, string, number]> = []
  const under = (p: string) => (f: string) => f === p || f.startsWith(p + "/")
  const deps: LinuxBuiltinDeps = {
    home: "/home/u",
    arch: opts.arch ?? "x64",
    fetch: (async (url: string) => {
      fetched.push(url)
      return routes[url]?.() ?? new Response("nope", { status: 404 })
    }) as unknown as typeof fetch,
    sha256: sha,
    untar: async (archive, dest, strip) => {
      untars.push([archive, dest, strip])
      for (const name of opts.unpack?.(dest, strip) ?? []) files.set(`${dest}/${name}`, new Uint8Array([1]))
    },
    hasAvx2: () => opts.avx2 ?? true,
    isMusl: () => opts.musl ?? false,
    fs: {
      exists: (p) => [...files.keys()].some(under(p)) || links.has(p),
      mkdir: () => {},
      write: (p, b) => { files.set(p, b) },
      rename: (from, to) => {
        for (const [f, b] of [...files]) if (under(from)(f)) { files.delete(f); files.set(to + f.slice(from.length), b) }
      },
      remove: (p) => { for (const f of [...files.keys()]) if (under(p)(f)) files.delete(f); links.delete(p) },
      chmod: (p, m) => { modes.set(p, m) },
      symlink: (target, path) => { links.set(path, target) },
    },
    log: (l) => lines.push(l),
    now: () => 42,
    pid: 7,
  }
  return { deps, files, links, modes, fetched, lines, untars }
}

describe("Cursor on Linux without curl", () => {
  const script = `DOWNLOAD_URL="https://downloads.cursor.com/lab/2026.10.01-e373342/\${OS}/\${ARCH}/agent-cli-package.tar.gz"`
  const TGZ = "https://downloads.cursor.com/lab/2026.10.01-e373342/linux/x64/agent-cli-package.tar.gz"

  test("reads the version from the vendor script", () => {
    expect(cursorVersionFromScript(script)).toBe("2026.10.01-e373342")
    expect(cursorVersionFromScript("echo nothing here")).toBeNull()
  })

  test("happy path: unpacked into versions/<ver>, cursor-agent and agent linked from ~/.local/bin", async () => {
    const l = fakeLinux({ [CURSOR_INSTALL_SCRIPT]: () => new Response(script), [TGZ]: () => new Response(new Uint8Array([9])) }, { unpack: () => ["cursor-agent", "node"] })
    await installCursorLinux(l.deps)
    const final = "/home/u/.local/share/cursor-agent/versions/2026.10.01-e373342"
    expect(l.files.has(`${final}/cursor-agent`)).toBe(true)
    expect(l.links.get("/home/u/.local/bin/cursor-agent")).toBe(`${final}/cursor-agent`)
    expect(l.links.get("/home/u/.local/bin/agent")).toBe(`${final}/cursor-agent`)
    expect(l.untars[0]![2]).toBe(1) // the package has one top-level dir
    expect([...l.files.keys()].some((f) => f.includes(".tmp-"))).toBe(false)
    expect(l.lines.join("\n")).toContain("publishes no checksum")
  })

  test("a package without cursor-agent is refused and leaves nothing", async () => {
    const l = fakeLinux({ [CURSOR_INSTALL_SCRIPT]: () => new Response(script), [TGZ]: () => new Response(new Uint8Array([9])) }, { unpack: () => ["README"] })
    const err = await installCursorLinux(l.deps).catch((e) => e)
    expect(String(err)).toContain("no cursor-agent")
    expect(l.files.size).toBe(0)
    expect(l.links.size).toBe(0)
  })

  test("arm64 downloads the arm64 package", async () => {
    const l = fakeLinux({ [CURSOR_INSTALL_SCRIPT]: () => new Response(script) }, { arch: "arm64" })
    await installCursorLinux(l.deps).catch(() => {})
    expect(l.fetched[1]).toBe("https://downloads.cursor.com/lab/2026.10.01-e373342/linux/arm64/agent-cli-package.tar.gz")
  })
})

describe("OpenCode on Linux without curl", () => {
  test("picks the asset opencode.ai/install would", () => {
    expect(openCodeLinuxAsset("x64", true, false)).toBe("opencode-linux-x64.tar.gz")
    expect(openCodeLinuxAsset("x64", false, false)).toBe("opencode-linux-x64-baseline.tar.gz")
    expect(openCodeLinuxAsset("x64", true, true)).toBe("opencode-linux-x64-musl.tar.gz")
    expect(openCodeLinuxAsset("arm64", false, false)).toBe("opencode-linux-arm64.tar.gz")
  })

  const TGZ = new Uint8Array([0x1f, 0x8b, 8, 0])
  const URL_ = "https://github.com/anomalyco/opencode/releases/download/v1.18.34/opencode-linux-x64.tar.gz"
  const release = (digest: string | null) => () => Response.json({
    tag_name: "v1.18.34",
    assets: [{ name: "opencode-linux-x64.tar.gz", browser_download_url: URL_, digest }],
  })

  test("happy path: digest verified, opencode 0755 in ~/.opencode/bin, staging removed", async () => {
    const l = fakeLinux({ [OPENCODE_LATEST_RELEASE_API]: release(`sha256:${sha(TGZ)}`), [URL_]: () => new Response(TGZ) }, { unpack: () => ["opencode"] })
    await installOpenCodeLinux(l.deps)
    expect(l.files.has("/home/u/.opencode/bin/opencode")).toBe(true)
    expect([...l.modes.values()]).toEqual([0o755])
    expect([...l.files.keys()].some((f) => f.includes(".tmp-"))).toBe(false)
    expect(l.lines.join("\n")).toContain("SHA-256 verified")
  })

  test("a digest mismatch refuses before unpacking", async () => {
    const l = fakeLinux({ [OPENCODE_LATEST_RELEASE_API]: release(`sha256:${"b".repeat(64)}`), [URL_]: () => new Response(TGZ) }, { unpack: () => ["opencode"] })
    const err = await installOpenCodeLinux(l.deps).catch((e) => e)
    expect(String(err)).toContain("checksum mismatch")
    expect(l.untars).toEqual([])
    expect(l.files.size).toBe(0)
  })

  test("no published digest: refused before downloading", async () => {
    const l = fakeLinux({ [OPENCODE_LATEST_RELEASE_API]: release(null) })
    const err = await installOpenCodeLinux(l.deps).catch((e) => e)
    expect(String(err)).toContain("no SHA-256 digest")
    expect(l.fetched).toEqual([OPENCODE_LATEST_RELEASE_API])
  })
})
