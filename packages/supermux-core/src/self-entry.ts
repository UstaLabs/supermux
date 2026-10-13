import { existsSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Subcommands a host binary that embeds supermux-core (a `bun build --compile` single file) must route
 * to the matching entry module, so the core can start its helper processes as `<binary> <subcommand>`:
 * a compiled binary has no sibling `keeper.js`/`bridge.js` on disk to hand to a child process.
 */
export const CORE_SUBCOMMANDS = { keeper: '__core-keeper', bridge: '__core-bridge' } as const

/** Inside a compiled Bun binary every module lives in the virtual filesystem (`/$bunfs/` or `B:\~BUN\`). */
export function isCompiledModuleUrl(url: string): boolean {
  const path = fileURLToPath(url)
  return path.startsWith('/$bunfs/') || /^[A-Za-z]:[\\/]~BUN[\\/]/.test(path)
}

export type SelfEntry = { command: string; args: string[]; cwd: string }

/**
 * How to start the helper whose source sits next to [moduleUrl] as `<name>.js` (dist) or `<name>.ts` (src):
 * `process.execPath <file>` from a checkout, `process.execPath <subcommand>` inside a compiled binary.
 */
export function selfEntry(moduleUrl: string, name: string, subcommand: string): SelfEntry {
  if (isCompiledModuleUrl(moduleUrl)) return { command: process.execPath, args: [subcommand], cwd: dirname(process.execPath) }
  const js = fileURLToPath(new URL(`./${name}.js`, moduleUrl))
  if (existsSync(js)) return { command: process.execPath, args: [js], cwd: dirname(js) }
  const ts = fileURLToPath(new URL(`./${name}.ts`, moduleUrl))
  if (existsSync(ts)) return { command: process.execPath, args: [ts], cwd: dirname(ts) }
  throw new Error(`${name} entry not found`)
}
