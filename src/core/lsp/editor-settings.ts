import { isServerInstalled, prereqMissing } from "./detect"
import { allServerSpecs, type LspServerSpec } from "./registry"
import { isLspServerEnabled, type EditorConfig } from "../settings/editor-config"

export type LspServerInstallState = "ready" | "missing" | "prereq-missing"

export interface LspServerSettingsRow {
  id: string
  label: string
  extensions: string[]
  enabled: boolean
  state: LspServerInstallState
  installLabel: string | null
  installable: boolean
  requires: string | null
  custom: boolean
  command?: string | null
}

function stateOf(spec: LspServerSpec): LspServerInstallState {
  if (isServerInstalled(spec)) return "ready"
  if (spec.install && prereqMissing(spec.install.requires)) return "prereq-missing"
  return "missing"
}

export function listLspServerSettingsRows(cfg: EditorConfig): LspServerSettingsRow[] {
  return allServerSpecs(cfg).map((spec) => ({
    id: spec.id,
    label: spec.label,
    extensions: spec.extensions,
    enabled: isLspServerEnabled(spec.id, cfg),
    state: stateOf(spec),
    installLabel: spec.install?.label ?? null,
    installable: !!spec.install,
    requires: spec.install?.requires ?? null,
    custom: !!spec.custom,
    command: spec.custom ? (spec.command ?? spec.bin) : null,
  }))
}
