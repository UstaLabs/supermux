import { defineStore } from "pinia"
import { ref } from "vue"
import { api, type GitRemoteStatus, type GitPushResult, type GitPullResult, type GitBranchList, type GitSwitchResult } from "@/api/client"

export type GitOp = "push" | "pull" | "fetch" | "publish" | "switch"
export type GitActionResult = GitPushResult | GitPullResult | GitSwitchResult

/** Non-success results are surfaced to the user as a card; successes just
 *  refresh status. invalid_name is excluded too — it renders inline in the
 *  picker's create field, not as a card. */
export function isActionableResult(r: GitActionResult): boolean {
  return r.status !== "pushed" && r.status !== "up_to_date" && r.status !== "clean"
    && r.status !== "switched" && r.status !== "invalid_name"
}

/** Other sessions whose workdir sits inside this repo checkout (they'd feel a
 *  branch switch under their feet). Worktree sessions live under
 *  ~/.mux/worktrees/…, so the prefix test naturally excludes them. */
export function sessionsSharingCheckout(
  sessions: { id: string; name: string; workdir: string }[],
  selfId: string,
  repoRoot: string | null,
): { id: string; name: string }[] {
  if (!repoRoot) return []
  const prefix = repoRoot.endsWith("/") ? repoRoot : repoRoot + "/"
  return sessions
    .filter((s) => s.id !== selfId)
    .filter((s) => s.workdir === repoRoot || s.workdir.startsWith(prefix))
    .map((s) => ({ id: s.id, name: s.name }))
}

export const useGitRemote = defineStore("gitRemote", () => {
  const statusBySession = ref<Record<string, GitRemoteStatus>>({})
  const busyBySession = ref<Record<string, GitOp | null>>({})
  const resultBySession = ref<Record<string, GitActionResult | null>>({})
  const branchesBySession = ref<Record<string, GitBranchList>>({})

  function setStatus(id: string, s: GitRemoteStatus) { statusBySession.value = { ...statusBySession.value, [id]: s } }
  function setBusy(id: string, op: GitOp | null) { busyBySession.value = { ...busyBySession.value, [id]: op } }
  function setResult(id: string, r: GitActionResult | null) { resultBySession.value = { ...resultBySession.value, [id]: r } }
  function dismiss(id: string) { setResult(id, null) }

  async function loadStatus(id: string) {
    try { setStatus(id, await api.gitStatus(id)) } catch { /* offline → keep last known */ }
  }

  async function loadBranches(id: string) {
    try { branchesBySession.value = { ...branchesBySession.value, [id]: await api.gitBranches(id) } } catch { /* offline → keep last known */ }
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

  /** Switch (or create) a branch; busy/result handling mirrors run(). Network
   *  errors become an error result instead of throwing. */
  async function switchBranch(id: string, name: string, create?: boolean): Promise<GitSwitchResult | undefined> {
    if (busyBySession.value[id]) return
    setBusy(id, "switch"); setResult(id, null)
    try {
      const r = await api.gitSwitch(id, name, create)
      setResult(id, isActionableResult(r) ? r : null)
      return r
    } catch (e: any) {
      const r: GitSwitchResult = { status: "error", message: e?.message ?? "switch failed" }
      setResult(id, r)
      return r
    } finally {
      setBusy(id, null)
      await Promise.all([loadStatus(id), loadBranches(id)])
    }
  }

  return { statusBySession, busyBySession, resultBySession, branchesBySession, loadStatus, loadBranches, run, switchBranch, dismiss }
})
