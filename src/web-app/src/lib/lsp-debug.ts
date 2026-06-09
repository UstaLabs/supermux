import { clientDebug } from "@/lib/client-debug"

export function lspDebug(event: string, data?: Record<string, unknown>): void {
  clientDebug("lsp", event, data)
}
