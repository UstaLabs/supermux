// supermux plugin lifecycle CLI.  Usage: bun run plugin <command> [args]
//
//   plugin list
//   plugin add <source> [--name N] [--ref R] [--scopes claude,codex,cursor]
//   plugin update <name>
//   plugin remove <name> [--purge]
//   plugin enable <name> [--scopes claude,codex,cursor]
//   plugin disable <name>
//
// <source> is a filesystem path, an https git URL, or github:org/repo.
import {
  listPlugins, addPlugin, updatePlugin, removePlugin, setPluginEnabled,
} from "../src/core/plugins/lifecycle"
import type { CliScope } from "../src/core/plugins/types"

const ALL_SCOPES: CliScope[] = ["claude", "codex", "cursor", "opencode", "gemini"]

function parseFlags(args: string[]): { positionals: string[]; flags: Record<string, string | boolean> } {
  const positionals: string[] = []
  const flags: Record<string, string | boolean> = {}
  for (let i = 0; i < args.length; i++) {
    const a = args[i]!
    if (a.startsWith("--")) {
      const key = a.slice(2)
      const next = args[i + 1]
      if (next !== undefined && !next.startsWith("--")) { flags[key] = next; i++ }
      else flags[key] = true
    } else positionals.push(a)
  }
  return { positionals, flags }
}

function parseScopes(v: string | boolean | undefined): CliScope[] | undefined {
  if (typeof v !== "string") return undefined
  const scopes = v.split(",").map((s) => s.trim()).filter(Boolean)
  for (const s of scopes) {
    if (!ALL_SCOPES.includes(s as CliScope)) { console.error(`unknown scope: ${s} (valid: ${ALL_SCOPES.join(", ")})`); process.exit(1) }
  }
  return scopes as CliScope[]
}

function printList(): void {
  const rows = listPlugins()
  if (rows.length === 0) { console.log("No plugins installed. Add one with: bun run plugin add <source>"); return }
  console.log("supermux plugins:\n")
  for (const r of rows) {
    const state = r.enabled ? "enabled" : "disabled"
    const cli = (["claude", "cursor", "codex"] as const).filter((c) => r.compatibility[c]).join(", ") || "none"
    const src = r.source.type === "git" ? r.source.url : r.source.type === "local" ? r.source.path : r.source.type
    console.log(`  ${r.name}${r.version ? `@${r.version}` : ""}  [${state}]`)
    console.log(`    scopes: ${r.scopes.join(", ") || "none"}   manifests: ${cli}`)
    console.log(`    source: ${src}\n`)
  }
}

async function main(): Promise<void> {
  const [cmd, ...rest] = process.argv.slice(2)
  const { positionals, flags } = parseFlags(rest)

  switch (cmd) {
    case "list":
      printList()
      break
    case "add": {
      const spec = positionals[0]
      if (!spec) { console.error("usage: plugin add <source> [--name N] [--ref R] [--scopes ...]"); process.exit(1) }
      const summary = await addPlugin(spec, {
        name: typeof flags.name === "string" ? flags.name : undefined,
        ref: typeof flags.ref === "string" ? flags.ref : undefined,
        scopes: parseScopes(flags.scopes),
      })
      console.log(`Installed ${summary.name}${summary.version ? `@${summary.version}` : ""} (scopes: ${summary.scopes.join(", ")}).`)
      break
    }
    case "update": {
      const name = positionals[0]
      if (!name) { console.error("usage: plugin update <name>"); process.exit(1) }
      await updatePlugin(name)
      console.log(`Updated ${name}.`)
      break
    }
    case "remove": {
      const name = positionals[0]
      if (!name) { console.error("usage: plugin remove <name> [--purge]"); process.exit(1) }
      await removePlugin(name, { purge: flags.purge === true })
      console.log(`Removed ${name}${flags.purge === true ? " (purged from disk)" : ""}.`)
      break
    }
    case "enable": {
      const name = positionals[0]
      if (!name) { console.error("usage: plugin enable <name> [--scopes ...]"); process.exit(1) }
      await setPluginEnabled(name, { enabled: true, scopes: parseScopes(flags.scopes) })
      console.log(`Enabled ${name}.`)
      break
    }
    case "disable": {
      const name = positionals[0]
      if (!name) { console.error("usage: plugin disable <name>"); process.exit(1) }
      await setPluginEnabled(name, { enabled: false })
      console.log(`Disabled ${name}.`)
      break
    }
    default:
      console.error("supermux plugin — commands: list | add | update | remove | enable | disable")
      process.exit(cmd ? 1 : 0)
  }
}

main().catch((err) => { console.error(`plugin: ${err?.message ?? err}`); process.exit(1) })
