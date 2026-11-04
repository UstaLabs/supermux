// @supermux/platform: the OS gaps supermux fills itself, each decided in one place (platform-layer spec §3.3).
// Everything the runtimes already do right (node:path, node:os, Bun.which, Bun.spawn) is called directly instead.
export * as proc from "./proc.js"
