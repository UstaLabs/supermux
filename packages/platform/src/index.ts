// @supermux/platform: the OS gaps supermux fills itself, each decided in one place (platform-layer spec §3.3).
// Everything the runtimes already do right (node:path, node:os, Bun.which, Bun.spawn) is called directly instead.
export { Unsupported } from "./errors.js"
export * as credentials from "./credentials.js"
export * as dirs from "./dirs.js"
export * as files from "./files.js"
export * as lock from "./lock.js"
export * as paths from "./paths.js"
export * as proc from "./proc.js"
export * as registry from "./registry.js"
