// Which OS this process runs on: the one read of it outside the runtimes (the os-check rule). Feature code takes the
// OS as a parameter that defaults to [os], so a test or a caller asks about any OS without touching process.platform.

/** The OS this process runs on. */
export function os(): NodeJS.Platform {
  return process.platform
}

/** Whether that is Windows. */
export function windows(): boolean {
  return os() === "win32"
}
