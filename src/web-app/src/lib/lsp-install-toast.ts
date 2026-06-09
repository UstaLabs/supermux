import { toast } from "vue-sonner"

/** Stable sonner id so progress updates replace the same snackbar. */
export function lspInstallToastId(serverId: string): string {
  return `lsp-install-${serverId}`
}

function trimLine(line: string, max = 140): string {
  const t = line.trim()
  if (t.length <= max) return t
  return `${t.slice(0, max - 1)}…`
}

export function beginLspInstall(label: string, serverId: string): string {
  const id = lspInstallToastId(serverId)
  toast.loading(`Installing ${label}…`, { id, description: "Starting…" })
  return id
}

export function tickLspInstall(toastId: string, label: string, line: string): void {
  toast.loading(`Installing ${label}…`, {
    id: toastId,
    description: trimLine(line) || "…",
  })
}

export function endLspInstall(
  toastId: string,
  label: string,
  ok: boolean,
  detail?: string,
): void {
  toast.dismiss(toastId)
  const desc = detail ? trimLine(detail, 200) : undefined
  if (ok) {
    toast.success(`${label} ready`, { description: desc ?? "Language support installed on the broker." })
  } else {
    toast.error(`${label} install failed`, {
      description: desc ?? "See broker logs for details.",
      duration: 12_000,
    })
  }
}
