import { posix } from "path"

export function safePipeComponent(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120)
}

export function usesFilesystemEndpoint(platform: NodeJS.Platform = process.platform): boolean {
  return platform !== "win32"
}

/** POSIX socket path always uses `/` so Windows test hosts match Linux production. */
export function localEndpoint(id: string, opts: { platform?: NodeJS.Platform; socketsDir: string }): string {
  return usesFilesystemEndpoint(opts.platform)
    ? posix.join(opts.socketsDir, `${id}.sock`)
    : `\\\\.\\pipe\\supermux-session-${safePipeComponent(id)}`
}
