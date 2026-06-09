import { makeLogger } from "../../../shared/log"
import { allocateFreePort, allocateDisplayNumber } from "../ports"
import type { DisplayProvider, DisplayInstance, ProvisionOpts } from "../types"

const log = makeLogger("display.linux-xvfb")

async function waitForPort(port: number, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const sock = await Bun.connect({ hostname: "127.0.0.1", port, socket: { data() {} } })
      sock.end()
      return true
    } catch {
      await new Promise((r) => setTimeout(r, 100))
    }
  }
  return false
}

export class LinuxXvfbProvider implements DisplayProvider {
  readonly name = "linux-xvfb" as const

  unavailableReason(hasBin: (bin: string) => boolean): string | null {
    if (!hasBin("Xvfb")) return "Xvfb not found — install xvfb (e.g. apt-get install xvfb)"
    if (!hasBin("x11vnc")) return "x11vnc not found — install x11vnc (e.g. apt-get install x11vnc)"
    return null
  }

  async provision(opts: ProvisionOpts): Promise<DisplayInstance> {
    const width = opts.width ?? 1280
    const height = opts.height ?? 800
    const dispNum = allocateDisplayNumber()
    const display = `:${dispNum}`
    const vncPort = await allocateFreePort()

    const xvfb = Bun.spawn(["Xvfb", display, "-screen", "0", `${width}x${height}x24`, "-nolisten", "tcp"], {
      stdout: "ignore",
      stderr: "pipe",
    })
    // Give Xvfb a moment to create its socket before x11vnc attaches.
    await new Promise((r) => setTimeout(r, 300))

    // From here on, any failure must reap the already-running Xvfb child —
    // otherwise it survives this process as an orphan headless display.
    let x11vnc: ReturnType<typeof Bun.spawn>
    try {
      // Bind the VNC server to loopback only — both IPv4 (127.0.0.1) and IPv6
      // (::1). NOTE: x11vnc's `-localhost` shortcut does NOT serve loopback
      // connections on every host (observed on x11vnc 0.9.17: it listens but
      // never accepts), and it leaves an IPv6 wildcard `[::]` socket open.
      // Explicit `-listen`/`-listen6` reliably serves AND keeps the bind
      // loopback-only (no 0.0.0.0 / [::] wildcard).
      x11vnc = Bun.spawn(
        ["x11vnc", "-display", display, "-rfbport", String(vncPort),
         "-listen", "127.0.0.1", "-listen6", "::1",
         "-forever", "-shared", "-nopw", "-quiet", "-noxdamage"],
        { stdout: "ignore", stderr: "pipe" },
      )
    } catch (err) {
      try { xvfb.kill() } catch {}
      throw err
    }

    const ready = await waitForPort(vncPort, 5000)
    if (!ready) {
      try { x11vnc.kill() } catch {}
      try { xvfb.kill() } catch {}
      throw new Error("x11vnc did not start listening within 5s")
    }
    log.info("xvfb_provisioned", { display, vncPort, xvfbPid: xvfb.pid, x11vncPid: x11vnc.pid })

    return {
      display,
      vncPort,
      teardown: async () => {
        try { x11vnc.kill() } catch {}
        try { xvfb.kill() } catch {}
        log.info("xvfb_torn_down", { display, vncPort })
      },
    }
  }
}
