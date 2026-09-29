const KNOWN = new Set(["ENOENT", "EACCES", "EPERM", "ENOTDIR", "EISDIR", "EEXIST", "EINVAL", "ENOTEMPTY", "EXDEV", "ELOOP", "ENAMETOOLONG", "EMFILE", "ENFILE"])

export class FsError extends Error {
  constructor(readonly code: string, message: string) {
    super(message)
    this.name = "FsError"
  }
}

/** Normalise any thrown value into an FsError with a stable code. */
export function toFsError(e: unknown): FsError {
  if (e instanceof FsError) return e
  const code = (e as { code?: unknown })?.code
  const message = (e as { message?: unknown })?.message
  const c = typeof code === "string" && KNOWN.has(code) ? (code === "EPERM" ? "EACCES" : code) : "EIO"
  return new FsError(c, typeof message === "string" ? message : String(e))
}
