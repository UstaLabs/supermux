import { describe, expect, test } from "bun:test"
import { createHash } from "crypto"
import { chmodSync, mkdirSync, mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import {
  HELPER_ABI,
  ZmxHelper,
  helperBinaries,
  verifyHelperManifest,
  zmxBinDir,
  type HelperBinaries,
} from "./helper"
import { encodeControl, encodeFrame, TAG_OUTPUT } from "./protocol"
import { isWorkspaceTerminalError, WorkspaceTerminalError } from "../workspace-backend"
import type { HelperEvent } from "./protocol"

const sha256 = (data: string | Uint8Array) => createHash("sha256").update(data).digest("hex")

/**
 * A fake helper: a real executable that writes whatever frames the test wants.
 * The point is to exercise OUR side — launch, manifest, framing, failure
 * mapping — without a zmx daemon, so these run anywhere.
 */
function fakeHelper(script: string, options: { abi?: number; manifest?: unknown } = {}): HelperBinaries {
  const dir = mkdtempSync(join(tmpdir(), "mux-zmx-helper-"))
  mkdirSync(join(dir, "bin"), { recursive: true })
  const jsPath = join(dir, "fake.js")
  writeFileSync(jsPath, `const ABI = ${options.abi ?? HELPER_ABI}\n${script}\n`)

  const helper = join(dir, "bin", "mux-zmx-helper")
  writeFileSync(helper, `#!/bin/sh\nexec ${process.execPath} ${jsPath} "$@"\n`)
  chmodSync(helper, 0o755)
  const zmx = join(dir, "bin", "zmx")
  writeFileSync(zmx, "#!/bin/sh\nexit 0\n")
  chmodSync(zmx, 0o755)

  const manifest = options.manifest ?? {
    schema: 1,
    abi: HELPER_ABI,
    target: "test",
    helper: { sha256: sha256(`#!/bin/sh\nexec ${process.execPath} ${jsPath} "$@"\n`) },
    zmx: { commit: "deadbeef", sha256: sha256("#!/bin/sh\nexit 0\n") },
    patch: { sha256: "n/a" },
  }
  writeFileSync(join(dir, "manifest.json"), JSON.stringify(manifest))
  return { dir, helper, zmx, manifest: join(dir, "manifest.json") }
}

/** Frames, written as the helper would: `out(tag, payload)` from the fake. */
const FAKE_PRELUDE = `
const out = (bytes) => process.stdout.write(bytes)
const control = (obj) => {
  const body = Buffer.from(JSON.stringify(obj))
  const head = Buffer.alloc(5); head[0] = 1; head.writeUInt32LE(body.length, 1)
  out(Buffer.concat([head, body]))
}
const data = (tag, body) => {
  const head = Buffer.alloc(5); head[0] = tag; head.writeUInt32LE(body.length, 1)
  out(Buffer.concat([head, Buffer.from(body)]))
}
const hello = () => control({ v: 1, ev: "hello", abi: ABI, zmx: "x", patch: "y", helper: "fake" })
`

type Collected = {
  events: HelperEvent[]
  output: Uint8Array[]
  failures: WorkspaceTerminalError[]
}

function collector(): { collected: Collected; handlers: Parameters<typeof ZmxHelper.launch>[0] } {
  const collected: Collected = { events: [], output: [], failures: [] }
  return {
    collected,
    handlers: {
      onOutput: bytes => { collected.output.push(bytes) },
      onEvent: event => { collected.events.push(event) },
      onFailure: error => { collected.failures.push(error) },
    },
  }
}

const waitFor = async (predicate: () => boolean, ms = 4000): Promise<void> => {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (predicate()) return
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  throw new Error("timed out waiting for the helper")
}

describe("helper binary resolution and manifest", () => {
  test("an explicit bin dir wins over everything else", () => {
    expect(zmxBinDir({ MUX_ZMX_BIN_DIR: "/opt/mux/zmx" } as NodeJS.ProcessEnv, "/state")).toBe("/opt/mux/zmx")
    const bins = helperBinaries("/opt/mux/zmx")
    expect(bins.helper).toBe("/opt/mux/zmx/bin/mux-zmx-helper")
    expect(bins.zmx).toBe("/opt/mux/zmx/bin/zmx")
    expect(bins.manifest).toBe("/opt/mux/zmx/manifest.json")
  })

  test("a matching manifest verifies", () => {
    const bins = fakeHelper("")
    const manifest = verifyHelperManifest(bins)
    expect(manifest.abi).toBe(HELPER_ABI)
  })

  test("a missing manifest is a backend failure, not a silent exec", () => {
    const bins = fakeHelper("")
    const broken = { ...bins, manifest: join(bins.dir, "nope.json") }
    expect(() => verifyHelperManifest(broken)).toThrow()
    try {
      verifyHelperManifest(broken)
    } catch (error) {
      expect(isWorkspaceTerminalError(error, "backend-unavailable")).toBe(true)
    }
  })

  test("a binary whose bytes do not match the manifest is refused", () => {
    const bins = fakeHelper("")
    // Somebody replaced the helper after the build wrote the manifest.
    writeFileSync(bins.helper, "#!/bin/sh\nexit 0\n")
    chmodSync(bins.helper, 0o755)
    try {
      verifyHelperManifest(bins)
      expect.unreachable()
    } catch (error) {
      expect(isWorkspaceTerminalError(error, "backend-unavailable")).toBe(true)
      expect((error as Error).message).toContain("manifest says")
    }
  })

  test("a manifest for another ABI is refused before the process starts", () => {
    const bins = fakeHelper("", { manifest: { schema: 1, abi: HELPER_ABI + 1, target: "test", helper: { sha256: "x" }, zmx: { commit: "x", sha256: "x" }, patch: { sha256: "x" } } })
    try {
      verifyHelperManifest(bins)
      expect.unreachable()
    } catch (error) {
      expect(isWorkspaceTerminalError(error, "backend-unavailable")).toBe(true)
      expect((error as Error).message).toContain("ABI")
    }
  })

  test("a manifest from a future schema is refused", () => {
    const bins = fakeHelper("", { manifest: { schema: 2, abi: HELPER_ABI, target: "t", helper: { sha256: "x" }, zmx: { commit: "x", sha256: "x" }, patch: { sha256: "x" } } })
    expect(() => verifyHelperManifest(bins)).toThrow()
  })
})

describe("helper process ownership", () => {
  test("hello, an ack and output all arrive in order", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
process.stdin.on("data", () => {
  data(2, "hello from the pty")
  control({ v: 1, ev: "ok", id: 1, result: { created: true } })
})
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => helper.hello !== null)
    expect(helper.hello?.abi).toBe(HELPER_ABI)

    const result = await helper.send({ op: "detach" })
    expect(result).toEqual({ created: true })
    expect(Buffer.concat(collected.output.map(b => Buffer.from(b))).toString()).toBe("hello from the pty")
    helper.kill()
  })

  test("a helper that speaks another ABI is a protocol failure", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
setTimeout(() => {}, 5000)
`, { abi: HELPER_ABI + 7 })
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => collected.failures.length > 0)
    expect(collected.failures[0]!.code).toBe("protocol")
    expect(collected.failures[0]!.recoverable).toBe(false)
    helper.kill()
  })

  test("a malformed stream is a protocol failure, and earlier frames still land", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
data(2, "real output")
out(Buffer.from([0x7f, 1, 0, 0, 0, 0]))   // a tag that is not ours
setTimeout(() => {}, 5000)
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => collected.failures.length > 0)
    expect(collected.failures[0]!.code).toBe("protocol")
    expect(Buffer.concat(collected.output.map(b => Buffer.from(b))).toString()).toBe("real output")
    helper.kill()
  })

  test("a helper that dies is a recoverable viewer failure, NEVER a target exit", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
process.stderr.write("something went wrong\\n")
process.exit(3)
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => collected.failures.length > 0)
    const failure = collected.failures[0]!
    expect(failure.code).toBe("backend-unavailable")
    expect(failure.recoverable).toBe(true)
    // The whole point: losing the helper says NOTHING about the shell.
    expect(collected.events.some(e => e.ev === "exit")).toBe(false)
    await waitFor(() => helper.stderr.includes("something went wrong"))
  })

  test("an exit event is passed through as the daemon stated it", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
control({ v: 1, ev: "exit", known: false, code: null, signal: null })
setTimeout(() => {}, 5000)
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => collected.events.some(e => e.ev === "exit"))
    const exit = collected.events.find(e => e.ev === "exit") as Extract<HelperEvent, { ev: "exit" }>
    // "known: false" is preserved rather than flattened to code 0.
    expect(exit).toEqual({ v: 1, ev: "exit", known: false, code: null, signal: null })
    expect(collected.failures.length).toBe(0)
    helper.kill()
  })

  test("a queue-overflow detach is recoverable; a refused handshake is not", async () => {
    const overflow = fakeHelper(`${FAKE_PRELUDE}
hello()
control({ v: 1, ev: "detached", code: 1, reason: "resync_required", message: "over cap" })
setTimeout(() => {}, 5000)
`)
    const a = collector()
    const h1 = await ZmxHelper.launch(a.handlers, { binaries: overflow })
    await waitFor(() => a.collected.failures.length > 0)
    expect(a.collected.failures[0]!.recoverable).toBe(true)
    h1.kill()

    const refused = fakeHelper(`${FAKE_PRELUDE}
hello()
control({ v: 1, ev: "failure", code: 1, name: "unsupported_version", message: "" })
setTimeout(() => {}, 5000)
`)
    const b = collector()
    const h2 = await ZmxHelper.launch(b.handlers, { binaries: refused })
    await waitFor(() => b.collected.failures.length > 0)
    expect(b.collected.failures[0]!.code).toBe("protocol")
    expect(b.collected.failures[0]!.recoverable).toBe(false)
    h2.kill()
  })

  test("an error reply rejects exactly the command it names", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
let n = 0
process.stdin.on("data", () => {
  n += 1
  if (n === 1) control({ v: 1, ev: "error", id: 1, code: "target-not-found", message: "no such target" })
  else control({ v: 1, ev: "ok", id: n })
})
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => helper.hello !== null)
    const error = await helper.send({ op: "attach", name: "n", socket: "/s", cols: 80, rows: 24 })
      .then(() => null, (e: unknown) => e)
    expect(isWorkspaceTerminalError(error, "target-not-found")).toBe(true)
    // The helper is still usable: one command failing is not a viewer failure.
    await helper.send({ op: "focus", active: true, cols: 80, rows: 24 })
    expect(collected.failures.length).toBe(0)
    helper.kill()
  })

  test("closing detaches the viewer and stops the process", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
process.stdin.on("data", () => { control({ v: 1, ev: "ok", id: 1 }); process.exit(0) })
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => helper.hello !== null)
    await helper.close()
    await waitFor(() => helper.exited)
    // An expected close is not a failure: the target is still out there.
    expect(collected.failures.length).toBe(0)
  })

  test("writes after the helper is gone are refused rather than thrown", async () => {
    const bins = fakeHelper(`${FAKE_PRELUDE}\nhello()\nprocess.exit(0)\n`)
    const { handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => helper.exited)
    expect(helper.write(new Uint8Array([1]))).toBe(false)
    expect(helper.reply(new Uint8Array([1]))).toBe(false)
    await expect(helper.send({ op: "detach" })).rejects.toThrow()
  })

  test("input is framed as input and replies as replies", async () => {
    // The fake echoes back what it was sent, tagged, so we can see which tag
    // each call used without reaching into private state.
    const bins = fakeHelper(`${FAKE_PRELUDE}
hello()
let buf = Buffer.alloc(0)
process.stdin.on("data", (chunk) => {
  buf = Buffer.concat([buf, chunk])
  while (buf.length >= 5) {
    const tag = buf[0], len = buf.readUInt32LE(1)
    if (buf.length < 5 + len) break
    const body = buf.subarray(5, 5 + len); buf = buf.subarray(5 + len)
    if (tag !== 1) data(2, tag + ":" + body.toString())
  }
})
`)
    const { collected, handlers } = collector()
    const helper = await ZmxHelper.launch(handlers, { binaries: bins })
    await waitFor(() => helper.hello !== null)
    expect(helper.write(new TextEncoder().encode("ls"))).toBe(true)
    expect(helper.reply(new TextEncoder().encode("\x1b[?62;c"))).toBe(true)
    await waitFor(() => Buffer.concat(collected.output.map(b => Buffer.from(b))).toString().includes("4:"))
    const echoed = Buffer.concat(collected.output.map(b => Buffer.from(b))).toString()
    expect(echoed).toContain("3:ls")
    expect(echoed).toContain("4:\x1b[?62;c")
    helper.kill()
  })
})

describe("frames the broker sends", () => {
  test("a control command carries our version and an id", () => {
    const framed = encodeControl({ v: 1, id: 5, op: "detach" })
    expect(framed[0]).toBe(1)
    const body = JSON.parse(new TextDecoder().decode(framed.slice(5)))
    expect(body).toEqual({ v: 1, id: 5, op: "detach" })
  })

  test("an output frame from the broker would be a protocol error", async () => {
    // Output is the helper's to produce. This pins that the tag exists and is
    // distinct; the helper rejects it on receipt (see main.zig).
    expect(encodeFrame(TAG_OUTPUT, new Uint8Array(0))[0]).toBe(TAG_OUTPUT)
  })
})
