import { defineStore } from "pinia"
import { ref } from "vue"
import { api, type GitRemoteStatus, type GitPushResult, type GitPullResult } from "@/api/client"

export type GitOp = "push" | "pull" | "fetch" | "publish"
export type GitActionResult = GitPushResult | GitPullResult

/** Non-success results are surfaced to the user as a card; successes just refresh status. */
export function isActionableResult(r: GitActionResult): boolean {
  return r.status !== "pushed" && r.status !== "up_to_date" && r.status !== "clean"
}

export const useGitRemote = defineStore("gitRemote", () => {
  const statusBySession = ref<Record<string, GitRemoteStatus>>({})
  const busyBySession = ref<Record<string, GitOp | null>>({})
  const resultBySession = ref<Record<string, GitActionResult | null>>({})

  function setStatus(id: string, s: GitRemoteStatus) { statusBySession.value = { ...statusBySession.value, [id]: s } }
  function setBusy(id: string, op: GitOp | null) { busyBySession.value = { ...busyBySession.value, [id]: op } }
  function setResult(id: string, r: GitActionResult | null) { resultBySession.value = { ...resultBySession.value, [id]: r } }
  function dismiss(id: string) { setResult(id, null) }

  async function loadStatus(id: string) {
    try { setStatus(id, await api.gitStatus(id)) } catch { /* offline → keep last known */ }
  }

  /** Run a remote op; sets busy for its duration, captures any actionable result,
   *  and always reloads status afterward. Returns the raw API result. */
  async function run(id: string, op: GitOp): Promise<GitActionResult | { ok: boolean; error?: string } | undefined> {
    if (busyBySession.value[id]) return
    setBusy(id, op); setResult(id, null)
    try {
      if (op === "fetch") return await api.gitFetch(id)
      const r = op === "publish" ? await api.gitPublish(id)
              : op === "push"    ? await api.gitPush(id)
              :                    await api.gitPull(id)
      setResult(id, isActionableResult(r) ? r : null)
      return r
    } finally {
      setBusy(id, null)
      await loadStatus(id)
    }
  }

  return { statusBySession, busyBySession, resultBySession, loadStatus, run, dismiss }
})
