import { existsSync, mkdirSync, writeFileSync } from "fs"
import { join } from "path"
import { buildAgentsMd } from "./index-builder"
import { home } from "../../shared/home"
import { TEMPLATES } from "./templates-embedded"

export function getMuxHome(): string {
  return process.env.MUX_HOME ?? join(home(), ".mux")
}

export function initMux(home?: string): string {
  const root = home ?? getMuxHome()

  if (existsSync(join(root, "agents.md"))) return root

  mkdirSync(root, { recursive: true, mode: 0o700 })
  mkdirSync(join(root, "personal"), { recursive: true })
  mkdirSync(join(root, "domains"), { recursive: true })
  mkdirSync(join(root, "skills"), { recursive: true })

  copyTemplate("conventions.md.tmpl", join(root, "conventions.md"))
  copyTemplate("identity.md.tmpl", join(root, "personal", "identity.md"))
  copyTemplate("preferences.md.tmpl", join(root, "personal", "preferences.md"))

  const inboxPath = join(root, "domains", "_inbox.md")
  if (!existsSync(inboxPath)) writeFileSync(inboxPath, "---\ndescription: Unsorted findings for the main agent to triage\ntags: []\n---\n\n# Inbox\n\n", { encoding: "utf8" })

  const agentsMd = buildAgentsMd(root)
  writeFileSync(join(root, "agents.md"), agentsMd, { encoding: "utf8" })

  return root
}

function copyTemplate(templateName: string, destPath: string): void {
  // Never clobber an existing file. A user's file can land BEFORE the mux home is
  // fully initialized (agents.md created), so initMux must seed missing files
  // only — overwriting here would wipe that customization.
  if (existsSync(destPath)) return
  const content = TEMPLATES[templateName]
  if (content === undefined) throw new Error(`unknown template: ${templateName}`)
  writeFileSync(destPath, content, { encoding: "utf8" })
}
