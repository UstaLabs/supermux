export interface AdbDevice { serial: string; state: string }

export function parseAdbDevices(stdout: string): AdbDevice[] {
  return stdout
    .split("\n")
    .slice(1) // drop "List of devices attached"
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => {
      const [serial, state] = l.split(/\s+/)
      return { serial: serial!, state: state ?? "unknown" }
    })
}

export async function listDevices(): Promise<AdbDevice[]> {
  const proc = Bun.spawn(["adb", "devices"], { stdout: "pipe", stderr: "ignore" })
  const out = await new Response(proc.stdout).text()
  await proc.exited
  return parseAdbDevices(out)
}

export function adbAvailable(hasBin: (b: string) => boolean): boolean {
  return hasBin("adb")
}
