import { defineStore } from "pinia"
import { ref, computed } from "vue"
import { toast } from "vue-sonner"
import { useSessions } from "../stores/sessions"
import { useMessages } from "../stores/messages"
import { useProxies } from "../stores/proxies"
import { useDisplays } from "../stores/displays"
import { useActivity } from "../stores/activity"
import { useAgentState } from "../stores/agentState"
import { useBgTasks } from "../stores/bgTasks"
import { useCommandsStore } from "../stores/commands"
import { useLsp } from "../stores/lsp"
import { useOnboarding } from "../stores/onboarding"
import { useSessionCache } from "../stores/sessionCache"
import { useFinishJob } from "../stores/finishJob"
import { useGitStatus } from "../stores/gitStatus"
import { useUnread } from "../stores/unread"
import { useDrafts } from "../stores/drafts"
import { router } from "../router"

const BACKOFF_MS = [1000, 2000, 4000, 8000, 30000]

export const useWS = defineStore("ws", () => {
  const status = ref<"offline" | "connecting" | "connected" | "reconnecting">("offline")
  const sessions = useSessions()
  const messages = useMessages()
  const proxies = useProxies()
  const displays = useDisplays()
  const activity = useActivity()
  const agentState = useAgentState()
  const bgTasks = useBgTasks()
  const commands = useCommandsStore()
  const onboarding = useOnboarding()
  const finishJob = useFinishJob()
  const gitStatus = useGitStatus()
  const unread = useUnread()
  const drafts = useDrafts()
  let ws: WebSocket | null = null
  let attempt = 0
  // Frames sent before the socket is OPEN (e.g. on the fast claim→connect path)
  // are queued and flushed on open — avoids "send on WebSocket: Still in CONNECTING state".
  let sendQueue: object[] = []

  // Cookie-only auth: the browser sends the HttpOnly cmux_token cookie on the
  // same-origin WS handshake automatically — no token in the URL.
  function connect() {
    open()
  }

  function open() {
    status.value = attempt === 0 ? "connecting" : "reconnecting"
    const proto = window.location.protocol === "https:" ? "wss" : "ws"
    ws = new WebSocket(`${proto}://${window.location.host}/ws`)
    ws.onopen = () => {
      attempt = 0
      status.value = "connected"
      ws!.send(JSON.stringify({ type: "subscribe" }))
      for (const f of sendQueue) ws!.send(JSON.stringify(f))
      sendQueue = []
    }
    ws.onmessage = (e) => {
      let frame: any
      try { frame = JSON.parse(String(e.data)) } catch { return }
      dispatch(frame)
    }
    ws.onclose = () => {
      status.value = "reconnecting"
      const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]!
      attempt++
      setTimeout(open, delay)
    }
    ws.onerror = () => { try { ws?.close() } catch {} }
  }

  function send(frame: object) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(frame))
    else sendQueue.push(frame)
  }

  const editorCallbacks = new Map<string, (paths: string[]) => void>()

  function onFsChanged(session: string, cb: (paths: string[]) => void) {
    editorCallbacks.set(session, cb)
  }
  function offFsChanged(session: string) {
    editorCallbacks.delete(session)
  }

  function navigateAwayFromKilledSession(id: string) {
    const r = router.currentRoute.value
    if (r.name !== "session-chat" || r.params.id !== id) return
    const desktop = window.matchMedia("(min-width: 1024px)").matches
    void router.push(desktop ? "/new" : "/")
  }

  function dispatch(frame: any) {
    if (frame.type === "ping") { send({ type: "pong" }); return }
    if (frame.type === "snapshot") {
      if (typeof frame.homeDir === "string") sessions.setHomeDir(frame.homeDir)
      sessions.replace(frame.sessions ?? [])
      for (const s of (frame.sessions ?? [])) { finishJob.fromSnapshot(s.id, s.finish_job); gitStatus.fromSnapshot(s.id, s.git) }
      if (frame.logs) for (const [s, log] of Object.entries(frame.logs)) messages.replace(s, log as any)
      if (frame.activity) for (const [s, list] of Object.entries(frame.activity)) activity.replace(s, list as any)
      if (frame.bgTasks) for (const [s, list] of Object.entries(frame.bgTasks)) bgTasks.set(s, list as any)
      if (frame.agentState) for (const [s, st] of Object.entries(frame.agentState)) agentState.set(s, st as any)
      if (frame.proxies) proxies.replace(frame.proxies.map((p: { isPublic?: boolean }) => ({ ...p, isPublic: !!p.isPublic })))
      if (frame.displays) displays.replace(frame.displays)
      if (frame.commands) commands.hydrate(frame.commands, frame.commandsResolved)
      if (frame.reads) unread.seed(frame.reads)
      if (frame.drafts) drafts.seed(frame.drafts)
      onboarding.setOnboarded(frame.onboarded ?? false)
    } else if (frame.type === "session_added")    { sessions.add(frame.session); finishJob.fromSnapshot(frame.session.id, frame.session.finish_job); gitStatus.fromSnapshot(frame.session.id, frame.session.git) }
    else if   (frame.type === "finish_job")        finishJob.set(frame.session, frame.job)
    else if   (frame.type === "session_git")       gitStatus.set(frame.session, frame.git)
    else if   (frame.type === "commands_changed") commands.set(frame.session, frame.commands, frame.resolved ?? true)
    else if   (frame.type === "session_removed")  {
      sessions.remove(frame.id)
      commands.remove(frame.id)
      useSessionCache().drop(frame.id)
      gitStatus.clear(frame.id)
      bgTasks.clear(frame.id)
      navigateAwayFromKilledSession(frame.id)
    }
    else if   (frame.type === "session_renamed")  sessions.rename(frame.id, frame.new)
    else if   (frame.type === "session_state")    sessions.updateState(frame.session, { mute: frame.mute, connected: frame.connected, model: frame.model, reasoningLevel: frame.reasoningLevel })
    else if   (frame.type === "session_read")     unread.setLastRead(frame.session, frame.last_read_at)
    else if   (frame.type === "draft_set")        drafts.applyRemote(frame.session, frame.text ?? "")
    else if   (frame.type === "draft_clear")      drafts.applyRemote(frame.session, "")
    else if   (frame.type === "message_append")   messages.append(frame.session, frame.entry)
    else if   (frame.type === "activity_append")  activity.append(frame.session, frame.event)
    else if   (frame.type === "bg_tasks")         bgTasks.set(frame.session, frame.tasks)
    else if   (frame.type === "agent_state")      agentState.set(frame.session, { state: frame.state, working: frame.working, detail: frame.detail, tool: frame.tool, since: frame.since, workingSince: frame.workingSince, waiting: frame.waiting, bgOpen: frame.bgOpen })
    else if   (frame.type === "agent_error")      toast.error(`${frame.session}: ${frame.errorMessage}`, { description: frame.errorType, duration: 10000 })
    else if   (frame.type === "message_update")   messages.update(frame.session, frame.entry_id, { text: frame.text, edited_at: frame.edited_at })
    else if   (frame.type === "message_reaction") messages.addReaction(frame.session, frame.entry_id, frame.emoji, frame.ts)
    else if   (frame.type === "proxy_created")    proxies.add(frame.proxy)
    else if   (frame.type === "proxy_updated")    proxies.add(frame.proxy)
    else if   (frame.type === "proxy_removed")    proxies.remove(frame.domain)
    else if   (frame.type === "proxy_status")     proxies.setStatus(frame.domain, frame.status)
    else if   (frame.type === "display_added")    displays.add(frame.display)
    else if   (frame.type === "display_removed")  displays.remove(frame.id)
    else if (frame.type === "fs_changed") {
      const cb = editorCallbacks.get(frame.session)
      if (cb) cb(frame.paths)
    }
    else if (frame.type === "agent_login_state") onboarding.setAgentLoginState(frame.kind, frame.state)
    else if (typeof frame.type === "string" && frame.type.startsWith("lsp_")) {
      // Lazy import avoids a store init cycle (lsp store calls back into useWS).
      useLsp().handleFrame(frame)
    }
  }

  return { status: computed(() => status.value), connect, send, onFsChanged, offFsChanged }
})
