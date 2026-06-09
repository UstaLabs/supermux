import type { KeybindingCommand, KeybindingContext } from "./types"

function requireSession(ctx: KeybindingContext): string | null {
  if (!ctx.sessionId || ctx.isSessionArchived) return null
  return ctx.sessionId
}

function togglePanel(
  ctx: KeybindingContext,
  panel: "terminal" | "editor" | "display",
): void {
  const sessionId = requireSession(ctx)
  if (!sessionId) return
  const panels = ctx.layout.panelsFor(sessionId)
  const openKey = `${panel}Open` as const
  const wasOpen = panels[openKey]
  if (panel === "terminal") ctx.layout.toggleTerminal(sessionId)
  else if (panel === "editor") ctx.layout.toggleEditor(sessionId)
  else ctx.layout.toggleDisplay(sessionId)
  if (!wasOpen && panels[openKey]) panels.activeTab = panel
}

export const KEYBINDING_COMMANDS: KeybindingCommand[] = [
  {
    id: "workspace.toggleSidebar",
    label: "Toggle session list",
    description: "Show or hide the session sidebar",
    category: "workspace",
    defaultChord: { mod: true, key: "b" },
    handler(ctx) {
      ctx.layout.toggleSidebarCollapsed()
    },
  },
  {
    id: "workspace.toggleChat",
    label: "Toggle chat",
    description: "Show or hide the message panel (when another panel is open)",
    category: "workspace",
    defaultChord: { mod: true, key: "l" },
    handler(ctx) {
      const sessionId = requireSession(ctx)
      if (!sessionId) return
      ctx.layout.toggleChat(sessionId)
    },
  },
  {
    id: "workspace.toggleTerminal",
    label: "Toggle terminal",
    description: "Open or close the session terminal",
    category: "workspace",
    defaultChord: { mod: true, key: "t" },
    handler(ctx) {
      togglePanel(ctx, "terminal")
    },
  },
  {
    id: "workspace.toggleEditor",
    label: "Toggle editor",
    description: "Open or close the session editor",
    category: "workspace",
    defaultChord: { mod: true, key: "e" },
    handler(ctx) {
      togglePanel(ctx, "editor")
    },
  },
  {
    id: "workspace.toggleDisplay",
    label: "Toggle display",
    description: "Open or close the session display stream",
    category: "workspace",
    defaultChord: { mod: true, key: "d" },
    handler(ctx) {
      togglePanel(ctx, "display")
    },
  },
  {
    id: "workspace.newSession",
    label: "New session",
    description: "Open the session launcher",
    category: "workspace",
    defaultChord: { mod: true, key: "n" },
    handler(ctx) {
      void ctx.router.push("/new")
    },
  },
]

export const KEYBINDING_COMMAND_MAP = new Map(
  KEYBINDING_COMMANDS.map((cmd) => [cmd.id, cmd]),
)
