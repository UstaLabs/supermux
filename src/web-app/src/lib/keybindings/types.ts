import type { Router, RouteLocationNormalizedLoaded } from "vue-router"
import type { useLayout } from "@/stores/layout"

export interface KeyChord {
  mod: boolean
  key: string
}

export type KeybindingCommandId =
  | "workspace.toggleSidebar"
  | "workspace.toggleChat"
  | "workspace.toggleTerminal"
  | "workspace.toggleEditor"
  | "workspace.toggleDisplay"
  | "workspace.newSession"

export interface KeybindingContext {
  layout: ReturnType<typeof useLayout>
  router: Router
  route: RouteLocationNormalizedLoaded
  sessionId?: string
  isSessionArchived?: boolean
}

export interface KeybindingCommand {
  id: KeybindingCommandId
  label: string
  description: string
  category: "workspace"
  defaultChord: KeyChord
  handler: (ctx: KeybindingContext) => void
}
