import { randomBytes } from "crypto"
import { existsSync } from "fs"
import type { Socket } from "bun"
import { makeLogger } from "../../../shared/log"
import { allocateFreePort } from "../ports"
import { ScrcpyVideoParser } from "./video"
import type { AccessUnit } from "./video"

const log = makeLogger("display.scrcpy")

const SERVER_JAR = "/usr/share/scrcpy/scrcpy-server"
const SERVER_VERSION = "3.3.4"
const HANDSHAKE = 1 + 64 + 12 // dummy + device name + codec meta

export interface ScrcpyInstance {
  serial: string
  width: number
  height: number
  configData: Uint8Array | null
  lastKeyFrame: Uint8Array | null
  onAccessUnit: (cb: (au: AccessUnit) => void) => void
  sendControl: (bytes: Uint8Array) => void
  teardown: () => Promise<void>
}

export function scrcpyUnavailableReason(hasBin: (b: string) => boolean): string | null {
  if (!hasBin("adb")) return "adb not found on PATH"
  if (!existsSync(SERVER_JAR)) return `scrcpy-server missing at ${SERVER_JAR}`
  return null
}

async function adb(args: string[]): Promise<void> {
  const proc = Bun.spawn(["adb", ...args], { stdout: "pipe", stderr: "pipe" })
  const code = await proc.exited
  if (code !== 0) {
    const err = await new Response(proc.stderr).text()
    throw new Error(`adb ${args.join(" ")} failed (${code}): ${err.trim()}`)
  }
}

// Connect to 127.0.0.1:<port>. adb's forward accepts the TCP connection even
// before scrcpy-server is listening on the localabstract socket, then resets it
// immediately. So a "successful" connect that closes right away must be retried.
// We consider a connection live once it survives a short grace window or
// receives data.
async function connectLive(
  port: number,
  handlers: { data: (s: Socket, c: Buffer) => void; close: () => void; error: (s: Socket, e: Error) => void },
  deadline: number,
): Promise<Socket> {
  let lastErr: unknown
  while (Date.now() < deadline) {
    let settled = false
    const early: Buffer[] = []
    const sock = await Bun.connect({
      hostname: "127.0.0.1",
      port,
      socket: {
        data: (s, c) => {
          if (settled) handlers.data(s, c)
          else early.push(c)
        },
        close: (s) => {
          if (settled) handlers.close()
        },
        error: (s, e) => {
          if (settled) handlers.error(s, e)
        },
      },
    }).catch((e) => {
      lastErr = e
      return null
    })
    if (!sock) {
      await new Promise((r) => setTimeout(r, 150))
      continue
    }
    // grace window: did the connection survive / produce data?
    await new Promise((r) => setTimeout(r, 250))
    // Bun Socket.readyState: positive (1 = Established, 2 = Else) means open/usable;
    // <= 0 means Closed (0), Detached (-1) or Shutdown (-2).
    const closed = sock.readyState <= 0
    if (!closed || early.length > 0) {
      settled = true
      for (const c of early) handlers.data(sock, c)
      return sock
    }
    try { sock.end() } catch {}
    await new Promise((r) => setTimeout(r, 150))
  }
  throw new Error(`could not establish live connection to 127.0.0.1:${port}: ${String(lastErr)}`)
}

export async function startScrcpy(serial: string, opts?: { maxSize?: number }): Promise<ScrcpyInstance> {
  const maxSize = opts?.maxSize ?? 0
  // scrcpy parses scid as a signed 32-bit hex int (Integer.parseInt(scid, 16)),
  // so it must fit in [0, 0x7FFFFFFF] — mask off the high bit. It is also
  // expected as exactly 8 hex chars (zero-padded).
  const scid = ((randomBytes(4).readUInt32BE(0) & 0x7fffffff) >>> 0)
    .toString(16)
    .padStart(8, "0")
  const port = await allocateFreePort()

  log.info("launching scrcpy server", { serial, scid, port, maxSize })

  await adb(["-s", serial, "push", SERVER_JAR, "/data/local/tmp/scrcpy-server.jar"])
  await adb(["-s", serial, "forward", `tcp:${port}`, `localabstract:scrcpy_${scid}`])

  const serverProc = Bun.spawn(
    [
      "adb",
      "-s",
      serial,
      "shell",
      "CLASSPATH=/data/local/tmp/scrcpy-server.jar",
      "app_process",
      "/",
      "com.genymobile.scrcpy.Server",
      SERVER_VERSION,
      `scid=${scid}`,
      "log_level=info",
      "tunnel_forward=true",
      "audio=false",
      "control=true",
      "video=true",
      "video_codec=h264",
      `max_size=${maxSize}`,
      "send_frame_meta=true",
    ],
    { stdout: "pipe", stderr: "pipe" },
  )

  const instance: ScrcpyInstance = {
    serial,
    width: 0,
    height: 0,
    configData: null,
    lastKeyFrame: null,
    onAccessUnit: () => {},
    sendControl: () => {},
    teardown: async () => {},
  }

  const parser = new ScrcpyVideoParser()
  let auCb: ((au: AccessUnit) => void) | null = null
  parser.onAccessUnit = (au) => {
    if (au.config && !instance.configData) instance.configData = au.data
    if (au.keyFrame) instance.lastKeyFrame = au.data
    auCb?.(au)
  }

  // Video socket: consume the 77-byte handshake, feed the rest to the parser.
  let head = new Uint8Array(0)
  let handshakeDone = false
  const onVideoData = (_s: Socket, chunk: Buffer) => {
    let bytes = new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength)
    if (!handshakeDone) {
      const merged = new Uint8Array(head.length + bytes.length)
      merged.set(head)
      merged.set(bytes, head.length)
      head = merged
      if (head.length < HANDSHAKE) return
      const dv = new DataView(head.buffer, head.byteOffset, head.byteLength)
      // skip dummy(1) + name(64); codec meta at offset 65: codecId(4) + width(4) + height(4)
      instance.width = dv.getUint32(65 + 4)
      instance.height = dv.getUint32(65 + 8)
      log.info("scrcpy handshake parsed", { width: instance.width, height: instance.height })
      const rest = head.slice(HANDSHAKE)
      handshakeDone = true
      head = new Uint8Array(0)
      if (rest.length > 0) parser.push(rest)
      return
    }
    parser.push(bytes)
  }

  // Give the server a moment to come up and listen on the localabstract socket.
  await new Promise((r) => setTimeout(r, 1500))

  const videoSock = await connectLive(
    port,
    {
      data: onVideoData,
      close: () => log.info("video socket closed"),
      error: (_s, e) => log.warn("video socket error", { err: String(e) }),
    },
    Date.now() + 6000,
  )

  const controlSock = await connectLive(
    port,
    {
      data: () => {},
      close: () => log.info("control socket closed"),
      error: (_s, e) => log.warn("control socket error", { err: String(e) }),
    },
    Date.now() + 6000,
  )

  let torn = false
  instance.onAccessUnit = (cb) => {
    auCb = cb
  }
  instance.sendControl = (bytes) => {
    controlSock.write(bytes)
    controlSock.flush()
  }
  instance.teardown = async () => {
    if (torn) return
    torn = true
    log.info("tearing down scrcpy", { serial, port })
    try { videoSock.end() } catch {}
    try { controlSock.end() } catch {}
    try { serverProc.kill() } catch {}
    try {
      await adb(["-s", serial, "forward", "--remove", `tcp:${port}`])
    } catch (e) {
      log.warn("forward --remove failed", { err: String(e) })
    }
  }

  return instance
}
