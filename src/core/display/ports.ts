import { existsSync } from "fs"

// Ask the OS for a free ephemeral port by binding to :0, then release it.
// There is a small race between release and the VNC server claiming it; this
// is acceptable for a self-hosted single-user broker.
export async function allocateFreePort(): Promise<number> {
  const server = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } })
  const port = server.port
  server.stop()
  return port
}

// Find an X display number whose unix socket does not already exist.
// X sockets live at /tmp/.X11-unix/X<n>. Start at 99 to avoid real displays.
export function allocateDisplayNumber(): number {
  for (let n = 99; n < 1000; n++) {
    if (!existsSync(`/tmp/.X11-unix/X${n}`)) return n
  }
  throw new Error("no free X display number in range 99-999")
}
