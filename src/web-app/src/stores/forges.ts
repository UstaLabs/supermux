import { defineStore } from "pinia"
import { ref } from "vue"
import { api, type ForgeConnection, type ForgeCliStatus, type ClonedRepo, type ForgeAddInput } from "@/api/client"

export const useForges = defineStore("forges", () => {
  const connections = ref<ForgeConnection[]>([])
  const cliStatus = ref<ForgeCliStatus | null>(null)
  const clonedRepos = ref<ClonedRepo[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function loadConnections() {
    loading.value = true; error.value = null
    try { const r = await api.listForges(); connections.value = r.connections; cliStatus.value = r.cli }
    catch (e: any) { error.value = e?.message ?? "failed to load connections" }
    finally { loading.value = false }
  }

  async function connect(input: ForgeAddInput) {
    error.value = null
    try { await api.addForge(input); await loadConnections() }
    catch (e: any) { error.value = e?.message ?? "failed to connect"; throw e }
  }

  async function importFromCli(kind: string, transport?: "https" | "ssh") {
    error.value = null
    try { await api.importForge(kind, transport); await loadConnections() }
    catch (e: any) { error.value = e?.message ?? "failed to import"; throw e }
  }

  async function disconnect(id: string) {
    try { await api.removeForge(id); await loadConnections() } catch (e: any) { error.value = e?.message ?? "failed to disconnect" }
  }

  async function loadCloned() {
    try { clonedRepos.value = (await api.listClonedRepos()).repos } catch (e: any) { error.value = e?.message ?? "failed to load repos" }
  }
  async function removeCloned(path: string) { try { await api.removeClonedRepo(path); await loadCloned() } catch (e: any) { error.value = e?.message ?? "failed to remove" } }
  async function pullCloned(path: string) { return api.pullClonedRepo(path) }

  return { connections, cliStatus, clonedRepos, loading, error, loadConnections, connect, importFromCli, disconnect, loadCloned, removeCloned, pullCloned }
})
