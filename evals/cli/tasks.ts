// The 13 tasks of the approved criteria (#156), in Ahmet's voice, none naming a tool. Each passes on the END STATE
// (state.json from the throwaway broker, the files the agent wrote) or, for questions, on a seeded fact it can't guess.
// T = tuning (the hint and help may be adjusted on these), H = held out (never used to adjust anything).
import { existsSync, readFileSync, readdirSync } from "node:fs"
import { join } from "node:path"
import { IDLE, IDS, SELF_ID, type World } from "./seed"

export interface Outcome { state: World; answer: string; rundir: string; workdir: string; issues: Array<{ title: string; priority: string }> }
export interface Task { id: string; split: "T" | "H" | "N"; area: string; prompt: string; meta?: string; check(o: Outcome): { pass: boolean; why: string } }

const newSessions = (o: Outcome) => o.state.sessions.filter((s) => s.created_by)
const inboxIds = (o: Outcome) => Object.keys(o.state.inboxes).filter((k) => o.state.inboxes[k]!.length)
const has = (re: RegExp, s: string) => re.test(s)

export const TASKS: Task[] = [
  { id: "01-tell-git", split: "T", area: "messaging", prompt: "tell the git session I'm done testing",
    check: (o) => {
      const got = inboxIds(o)
      const msg = o.state.inboxes[IDS.git]?.map((m) => m.text).join(" ") ?? ""
      if (got.length !== 1 || got[0] !== IDS.git) return { pass: false, why: `inboxes written: ${got.join(",") || "none"}` }
      return has(/test/i, msg) ? { pass: true, why: "Git cleanup got it" } : { pass: false, why: `message lacks the point: ${msg.slice(0, 80)}` }
    } },
  { id: "02-idle-broadcast", split: "H", area: "messaging, many", prompt: "let everyone who's idle know the broker restarts at 22:00",
    check: (o) => {
      const got = inboxIds(o).sort()
      const want = [...IDLE].sort()
      const okText = got.every((id) => o.state.inboxes[id]!.some((m) => /22[:.]?00|10 ?pm/i.test(m.text)))
      return JSON.stringify(got) === JSON.stringify(want) && okText ? { pass: true, why: "exactly the 3 idle sessions" } : { pass: false, why: `messaged ${got.length}: ${got.map((g) => g.slice(0, 4)).join(",")}${okText ? "" : " (time missing)"}` }
    } },
  { id: "03-who-browser", split: "T", area: "finding sessions", prompt: "who's working on the browser tab?",
    check: (o) => has(/#?105|9b1e44a0/i, o.answer) ? { pass: true, why: "named Browser tab (#105)" } : { pass: false, why: "did not name the #105 session" } },
  { id: "04-zmx-fix", split: "H", area: "past sessions", prompt: "how did we fix the zmx crash last week?",
    check: (o) => has(/clamp/i, o.answer) || (has(/at least 1|minimum of 1|>= ?1/i, o.answer) && has(/row/i, o.answer)) ? { pass: true, why: "found the clamp fix" } : { pass: false, why: "no clamp/0-rows fix in the answer" } },
  { id: "05-codex-limit", split: "T", area: "memory search", prompt: "what do we know about the Codex usage limit?",
    check: (o) => has(/17(th)? Nov|Nov(ember)? 17|11-17|17\.11/i, o.answer) ? { pass: true, why: "gave the 17 Nov reset" } : { pass: false, why: "no reset date" } },
  { id: "06-remember-fold", split: "H", area: "memory write (control)", prompt: "remember that the Fold's versionCode is 2008",
    check: (o) => {
      const root = join(o.rundir, "mux", "domains")
      const hit = readdirSync(root).filter((f) => f.endsWith(".md") && !f.endsWith(".digest.md")).find((f) => { const t = readFileSync(join(root, f), "utf8"); return /2008/.test(t) && /fold/i.test(t) })
      return hit ? { pass: true, why: `saved in ${hit}` } : { pass: false, why: "not in the shared memory domains" }
    } },
  { id: "07-rides-chart", split: "T", area: "HTML card", prompt: "show me a chart of my rides this week: 32, 0, 45, 18, 0, 60, 25 km",
    check: (o) => {
      const c = o.state.cards.find((c) => /<svg|<canvas|chart|<div[^>]*(bar|height)/i.test(c.html) && /60/.test(c.html) && /45/.test(c.html))
      return c ? { pass: true, why: `card "${c.title}"` } : { pass: false, why: o.state.cards.length ? "a card without the chart data" : "no card" }
    } },
  { id: "08-send-screenshot", split: "H", area: "attachments", prompt: "send me the home screenshot in out/",
    check: (o) => o.state.attachments.some((a) => a.files.some((f) => /out\/home\.png$/.test(f))) ? { pass: true, why: "attached out/home.png" } : { pass: false, why: o.state.attachments.length ? "attached the wrong file" : "nothing attached" } },
  { id: "09-read-file", split: "T", area: "download", prompt: "what's in the file I just sent?",
    meta: '<channel source="supermux" chat_id="web:Mobile Pwa" message_id="m_9c2" user="ahmet" attachment_file_id="f_7a3e91" attachment_name="test-notes.txt" attachments="[{&quot;file_id&quot;:&quot;f_7a3e91&quot;,&quot;name&quot;:&quot;test-notes.txt&quot;}]">',
    check: (o) => o.state.downloads.includes("f_7a3e91") && has(/900 ?ms|6\.5 ?h|2008/i, o.answer) ? { pass: true, why: "downloaded and summarised" } : { pass: false, why: o.state.downloads.length ? "downloaded, but the answer lacks its content" : "never downloaded the file" } },
  { id: "10-spawn", split: "T", area: "spawn", prompt: "start a session to look into the flaky Windows storage test",
    check: (o) => {
      const n = newSessions(o)
      if (n.length !== 1) return { pass: false, why: `${n.length} new sessions` }
      return has(/windows|storage|flaky/i, n[0]!.name + " " + (n[0]!.task ?? "")) ? { pass: true, why: `"${n[0]!.name}" via ${n[0]!.created_by}` } : { pass: false, why: `unrelated name "${n[0]!.name}"` }
    } },
  // Was "get Codex to review…" (11-delegate-codex). Stage 1 showed it can't pass: the memory seeded for task 05 says the only
  // Codex account is at its limit and not to start Codex sessions, so agents rightly refused in BOTH arms. Same skill, Claude.
  { id: "11-delegate-claude", split: "H", area: "delegate", prompt: "get another Claude session to review src/cli.ts, read-only, and report back to you",
    check: (o) => {
      const d = newSessions(o).filter((s) => s.created_by === "delegate" && s.agent === "claude")
      if (d.length !== 1) return { pass: false, why: `${d.length} claude delegates (sessions made: ${newSessions(o).map((s) => s.created_by + ":" + s.agent).join(",") || "none"})` }
      return d[0]!.access === "dont-ask" && /cli\.ts/.test(d[0]!.task ?? "") ? { pass: true, why: "claude, read-only, cli.ts" } : { pass: false, why: `access ${d[0]!.access}, task mentions cli.ts: ${/cli\.ts/.test(d[0]!.task ?? "")}` }
    } },
  { id: "12-public-link", split: "T", area: "ports", prompt: "give me a link to the dev server on 5173 I can send a friend",
    check: (o) => {
      const p = o.state.proxies.find((p) => p.port === 5173)
      if (!p) return { pass: false, why: "no proxy for 5173" }
      if (!p.public) return { pass: false, why: "exposed, but not public" }
      return o.answer.includes(p.url) ? { pass: true, why: "public link given" } : { pass: false, why: "public, but the URL is not in the answer" }
    } },
  { id: "13-board", split: "H", area: "the board (control)", prompt: "put 'Fold crashes on rotate' on the board, high priority",
    check: (o) => o.issues.some((i) => /fold crashes on rotate/i.test(i.title) && i.priority === "high") ? { pass: true, why: "issue filed, high" } : { pass: false, why: `issues: ${o.issues.map((i) => `${i.title}/${i.priority}`).join("; ") || "none"}` } },

  // ---- Fresh held-out tasks (split "N"), written 2026-11-15 by a separate agent that saw only seed.ts and broker.ts,
  // never the runs or results, and frozen before the Stage 2 rerun. Two edits on review: 14's link moved off :5173
  // (task 12 could have reused it); 20 no longer requires asleep sessions on a card of "what's running".
  { id: "14-proxy-private", split: "N", area: "make a public link private again", prompt: "the fold preview link is still public, lock it back down. keep it running tho, I still need it",
    check: (o) => {
      const fp = o.state.proxies.find((p) => p.domain === "fold-preview"), dd = o.state.proxies.find((p) => p.domain === "docs-dev")
      if (!fp) return { pass: false, why: "fold-preview was removed (should only be made private)" }
      if (fp.public) return { pass: false, why: "fold-preview is still public" }
      if (fp.port !== 4173) return { pass: false, why: `fold-preview now points at port ${fp.port}` }
      if (!dd || !dd.public) return { pass: false, why: "docs-dev was touched" }
      return { pass: true, why: "fold-preview private, docs-dev untouched" }
    } },
  { id: "15-message-two", split: "N", area: "message two named sessions", prompt: "tell tally and the browser tab session that main is red on CI, no merges till I say so",
    check: (o) => {
      const ids = inboxIds(o).sort(), want = [IDS.tally, IDS.browser].sort()
      if (JSON.stringify(ids) !== JSON.stringify(want)) return { pass: false, why: `messaged ${ids.map((i) => i.slice(0, 4)).join(",") || "nobody"}` }
      return want.every((id) => o.state.inboxes[id]!.some((m) => /\bci\b|red|merg/i.test(m.text))) ? { pass: true, why: "both told, nobody else" } : { pass: false, why: "a message lacks CI/merges" }
    } },
  { id: "16-kill", split: "N", area: "kill a session", prompt: "job hunter's done, kill that session",
    check: (o) => {
      const j = o.state.sessions.find((s) => s.id === IDS.jobs)!
      const others = o.state.sessions.filter((s) => s.id !== IDS.jobs && s.state === "killed")
      if (!(j.archived && j.state === "killed")) return { pass: false, why: `Job Hunter is ${j.state}` }
      return others.length ? { pass: false, why: `also killed: ${others.map((s) => s.name).join(", ")}` } : { pass: true, why: "Job Hunter killed, nothing else" }
    } },
  { id: "17-council", split: "N", area: "two agents, one question", prompt: "get claude and grok both to weigh in: should the v0.17.7 train wait until the mac is cleaned up? want both takes",
    check: (o) => {
      const c = o.state.councils.find((c) => Array.isArray(c.members) && c.members.length >= 2 && /claude/i.test(JSON.stringify(c.members)) && /grok/i.test(JSON.stringify(c.members)))
      if (c) return /mac|0\.17|train|release|clean/i.test(c.question) ? { pass: true, why: "council with claude + grok" } : { pass: false, why: `council off-topic: ${c.question}` }
      const ns = newSessions(o)
      if (ns.some((s) => s.agent === "claude") && ns.some((s) => s.agent === "grok")) return { pass: true, why: "a claude and a grok session started" }
      return { pass: false, why: `no claude+grok council or sessions (councils ${o.state.councils.length}, new: ${ns.map((s) => s.agent).join(",") || "none"})` }
    } },
  { id: "18-transcript-followup", split: "N", area: "past transcript → live session", prompt: "that zmx rotation crash fix from last week, have the git cleanup session cherry-pick it onto the release branch",
    check: (o) => {
      if (!(o.state.inboxes[IDS.git] ?? []).some((m) => m.text.includes("4f2a9c1"))) return { pass: false, why: "Git cleanup got no message with 4f2a9c1" }
      const stray = inboxIds(o).filter((id) => id !== IDS.git)
      return stray.length ? { pass: false, why: `also messaged ${stray.length}` } : { pass: true, why: "Git cleanup told to cherry-pick 4f2a9c1" }
    } },
  { id: "19-memory-to-session", split: "N", area: "memory → sleeping session", prompt: "remind the release train session about the mac disk space thing before it does a build",
    check: (o) => {
      const msgs = o.state.inboxes[IDS.release] ?? []
      if (!msgs.length) return { pass: false, why: "Release train got no message" }
      return msgs.some((m) => /deriveddata|derived data|6\.6/i.test(m.text)) ? { pass: true, why: "told about DerivedData / 6.6 GB" } : { pass: false, why: "message lacks the memory fact" }
    } },
  { id: "20-sessions-card", split: "N", area: "live sessions as a card", prompt: "make me a card of what's running right now, which agent + model each one's on",
    check: (o) => {
      const html = o.state.cards.map((c) => c.html).join("\n").toLowerCase()
      if (!html) return { pass: false, why: "no card shown" }
      const miss = ["tally", "git cleanup", "browser tab", "infinite calm", "job hunter", "grok-4.7", "gpt-5.6-luna", "sonnet", "opus"].filter((n) => !html.includes(n))
      if (miss.length) return { pass: false, why: `card missing: ${miss.join(", ")}` }
      return /zmx resize|fold build|docs site/.test(html) ? { pass: false, why: "lists archived sessions" } : { pass: true, why: "all live sessions with models" }
    } },
  { id: "21-memory-to-board", split: "N", area: "memory → the board", prompt: "put something on the board to restart the git cleanup once codex resets, date in the title",
    check: (o) => {
      const hit = o.issues.find((i) => /\b17\b/.test(i.title) && /git|codex|clean/i.test(i.title))
      return hit ? { pass: true, why: `issue: ${hit.title}` } : { pass: false, why: `no issue with the 17 Nov date (${o.issues.map((i) => i.title).join(" | ") || "none"})` }
    } },
]

export const SELF = SELF_ID
export const fileExists = existsSync
