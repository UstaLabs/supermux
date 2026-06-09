import { test, expect } from "bun:test"
import { LinuxXvfbProvider } from "../src/core/display/providers/linux-xvfb"

function hasBin(bin: string): boolean {
  return Bun.spawnSync(["which", bin], { stdout: "ignore", stderr: "ignore" }).exitCode === 0
}

const canRun = process.platform === "linux" && hasBin("Xvfb") && hasBin("x11vnc")

test.skipIf(!canRun)("xvfb provider yields a live RFB server", async () => {
  const provider = new LinuxXvfbProvider()
  const inst = await provider.provision({ width: 640, height: 480 })
  try {
    const greeting = await new Promise<string>((res, rej) => {
      const chunks: Uint8Array[] = []
      Bun.connect({
        hostname: "127.0.0.1",
        port: inst.vncPort,
        socket: {
          data(_s, d) { chunks.push(d); res(new TextDecoder().decode(Buffer.concat(chunks))) },
          error() { rej(new Error("connect error")) },
        },
      }).catch(rej)
      setTimeout(() => rej(new Error("timeout")), 4000)
    })
    expect(greeting.startsWith("RFB ")).toBe(true)
  } finally {
    await inst.teardown()
  }
})
