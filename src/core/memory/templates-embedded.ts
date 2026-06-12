// The memory seed templates, embedded as text so the compiled binary doesn't
// read the repo tree at runtime. Source mode gets identical bytes (bun loads
// type:"text" imports from disk). Fixed, small set — keep in sync with
// src/core/memory/templates/ (the test asserts byte equality).
import agents from "./templates/agents.md.tmpl" with { type: "text" }
import conventions from "./templates/conventions.md.tmpl" with { type: "text" }
import domain from "./templates/domain.md.tmpl" with { type: "text" }
import identity from "./templates/identity.md.tmpl" with { type: "text" }
import preferences from "./templates/preferences.md.tmpl" with { type: "text" }
import soul from "./templates/soul.md.tmpl" with { type: "text" }

export const TEMPLATES: Record<string, string> = {
  "agents.md.tmpl": agents,
  "conventions.md.tmpl": conventions,
  "domain.md.tmpl": domain,
  "identity.md.tmpl": identity,
  "preferences.md.tmpl": preferences,
  "soul.md.tmpl": soul,
}
