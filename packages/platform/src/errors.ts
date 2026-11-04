/**
 * What a gap answers where the OS or the runtime has no way to do it (a file link without the privilege, an OS
 * credential store without ffi). Typed, so a caller falls back on purpose (a link becomes a copy, a probe reads as
 * "no") instead of matching a message.
 */
export class Unsupported extends Error {
  readonly code = "unsupported" as const
  constructor(readonly what: string, detail: string) {
    super(`${what} is not supported here: ${detail}`)
    this.name = "Unsupported"
  }
}
