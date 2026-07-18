import { join } from "path"

export function safePipeComponent(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120)
}

export function localEndpoint(id: string, opts: { platform?: NodeJS.Platform; socketsDir: string }): string {
  return (opts.platform ?? process.platform) === "win32"
    ? `\\\\.\\pipe\\supermux-session-${safePipeComponent(id)}`
    : join(opts.socketsDir, `${id}.sock`)
}
