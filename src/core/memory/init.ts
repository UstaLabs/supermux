import { existsSync, mkdirSync, writeFileSync, readFileSync } from "fs"
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

  copyTemplate("soul.md.tmpl", join(root, "soul.md"))
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
  // Never clobber an existing file. A user can write a custom soul.md (via the
  // onboarding soul editor → setSoul) BEFORE the mux home is fully initialized
  // (agents.md created), so initMux must seed missing files only — overwriting
  // here would wipe that customization.
  if (existsSync(destPath)) return
  const content = TEMPLATES[templateName]
  if (content === undefined) throw new Error(`unknown template: ${templateName}`)
  writeFileSync(destPath, content, { encoding: "utf8" })
}

// Personalize the personal-assistant's soul.md with the name the user chose in
// the wizard, so a fresh PA knows who it is the SAME way an established one does:
// the PA is told to read soul.md, and a curated soul.md opens with "You are
// <name>" (this is exactly why the user's "dockie" knows its name while a fresh
// install — whose soul.md is the untouched template — fell back to "Claude Code").
//
// Only rewrites the PRISTINE default template; if soul.md has been customized at
// all, it's left alone. Idempotent + safe to call on every PA spawn.
export function seedSoulName(name: string, root?: string): void {
  const home = root ?? getMuxHome()
  initMux(home)
  const soulPath = join(home, "soul.md")
  const template = TEMPLATES["soul.md.tmpl"]!

  let current = ""
  try { current = readFileSync(soulPath, "utf8") } catch {}
  if (current.trim() !== template.trim()) return // user-customized → don't touch

  const seeded = template
    .replace("# Soul", () => `# ${name}`)
    .replace("define your main agent's personality.", () => `define ${name}'s personality.`)
    .replace(
      "- You are the user's personal AI assistant and engineering partner.",
      () => `- You are ${name}, the user's personal AI assistant and engineering partner.`,
    )
  writeFileSync(soulPath, seeded, { encoding: "utf8" })
}
