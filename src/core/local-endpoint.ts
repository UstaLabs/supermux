// platform-ok: a POSIX socket path is /-spelled on any host, so Windows test hosts match Linux
import { posix, win32 } from "path"
import { host } from "@supermux/platform"

export function safePipeComponent(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120)
}

/**
 * A session socket is a file on every OS (#156). Windows used named pipes until then; it now uses AF_UNIX
 * (Windows 10+), because only an AF_UNIX connection names its peer process (SIO_AF_UNIX_GETPEERPID): Bun exposes no
 * handle for a named pipe. sun_path holds 108 bytes, so a Windows path longer than this keeps the pipe.
 */
export const MAX_WINDOWS_SOCKET_PATH = 100

/** The id of the operator socket (#156): `supermux` from outside any session, acting as the machine's owner. */
export const OPERATOR_ENDPOINT_ID = "operator"

/** The named pipe a session used on Windows before #156: still served for agents (and their shims) started before. */
export function legacyPipeEndpoint(id: string): string {
  return `\\\\.\\pipe\\supermux-session-${safePipeComponent(id)}`
}

/** POSIX socket path always uses `/` so Windows test hosts match Linux production. */
export function localEndpoint(id: string, opts: { platform?: NodeJS.Platform; socketsDir: string }): string {
  if ((opts.platform ?? host.os()) !== "win32") return posix.join(opts.socketsDir, `${id}.sock`)
  const file = win32.join(opts.socketsDir, `${id}.sock`)
  return file.length <= MAX_WINDOWS_SOCKET_PATH ? file : legacyPipeEndpoint(id)
}

/**
 * Where a client tries to reach session `id`, in order. On Windows: the AF_UNIX file, then the legacy pipe, so a client
 * whose broker has not restarted onto this code yet (it serves only the pipe) still connects.
 */
export function localEndpoints(id: string, opts: { platform?: NodeJS.Platform; socketsDir: string }): string[] {
  const primary = localEndpoint(id, opts)
  return (opts.platform ?? host.os()) === "win32" && primary !== legacyPipeEndpoint(id) ? [primary, legacyPipeEndpoint(id)] : [primary]
}

/** True for an endpoint that is a file (unlinked before listening, made private after). */
export function isFileEndpoint(endpoint: string): boolean {
  return !endpoint.startsWith("\\\\.\\pipe\\")
}
