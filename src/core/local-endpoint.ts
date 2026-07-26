import { join } from "path"

export function safePipeComponent(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120)
}

export function usesFilesystemEndpoint(platform: NodeJS.Platform = process.platform): boolean {
  return platform !== "win32"
}

export function localEndpoint(id: string, opts: { platform?: NodeJS.Platform; socketsDir: string }): string {
  return usesFilesystemEndpoint(opts.platform)
    ? join(opts.socketsDir, `${id}.sock`)
    : `\\\\.\\pipe\\supermux-session-${safePipeComponent(id)}`
}
