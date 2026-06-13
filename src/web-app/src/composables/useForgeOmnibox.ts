import { ref, watch, type Ref } from "vue"
import { api, type RemoteRepo } from "@/api/client"
import { useForges } from "@/stores/forges"
import type { OmniOption } from "@/lib/forge-omnibox"

/** Debounced cloud search + resolve-a-chosen-option-to-a-local-path (clone/create as needed). */
export function useForgeOmnibox(query: Ref<string>) {
  const forges = useForges()
  const cloudRepos = ref<RemoteRepo[]>([])
  const searching = ref(false)
  const resolving = ref(false)
  let seq = 0
  let timer: ReturnType<typeof setTimeout> | null = null

  watch(query, (q) => {
    const trimmed = q.trim()
    if (timer) clearTimeout(timer)
    if (forges.connections.length === 0 || trimmed.length < 2) { cloudRepos.value = []; return }
    timer = setTimeout(async () => {
      const mine = ++seq
      searching.value = true
      try { const r = await api.searchForge(trimmed); if (mine === seq) cloudRepos.value = r.repos }
      catch { if (mine === seq) cloudRepos.value = [] }
      finally { if (mine === seq) searching.value = false }
    }, 250)
  })

  /** Resolve a chosen option to a local workdir path. */
  async function resolve(opt: OmniOption): Promise<string> {
    if (opt.kind === "local") return opt.path
    resolving.value = true
    try {
      if (opt.kind === "cloud") return (await api.cloneForge(opt.repo.connectionId, opt.repo.owner, opt.repo.name)).localPath
      if (opt.createTarget === "local") return (await api.createLocalRepo(query.value.trim())).localPath
      return (await api.createForge({ connectionId: opt.createTarget, name: query.value.trim(), private: true })).localPath
    } finally { resolving.value = false }
  }

  return { cloudRepos, searching, resolving, resolve }
}
