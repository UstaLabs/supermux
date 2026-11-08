// Scores one run from what the stub server recorded (calls.jsonl), the shown page and the agent's own output.
import type { EvalPrompt } from "./prompts"
import type { Problem } from "./check"
import type { Usage } from "./agents"

export interface Call {
  seq: number
  tool: "show_html" | "preview_html" | "attach"
  error?: string
  page?: string
  title?: string
  forced?: boolean
  checked?: boolean
  shown?: boolean
  problems?: Problem[]
  unpromptedSends?: string[]
  files?: string[]
  hash?: string
  background?: string
}

export interface Score {
  /** Ended with a shown card when one was expected; called neither tool when none was. */
  toolUse: boolean
  shows: number
  previews: number
  /** B's habit: a preview_html before the first show_html. */
  previewedFirst?: boolean
  /** A mermaid diagram answered a prompt where one is as good as a card. */
  diagram?: boolean
  /** The first page it checked had no real problem (forced ones don't count). */
  firstClean?: boolean
  realProblems: Problem["kind"][]
  /** After any problem report, a later show_html was shown; and how many show_html calls that took. */
  repaired?: boolean
  tries?: number
  /** The shown page: no local files, the theme variables, no page background of its own. */
  selfContained?: boolean
  usesTheme?: boolean
  transparent?: boolean
  /** Where the prompt asks for a choice: a real click on the page sent one. */
  sendWorks?: boolean
  unpromptedSends: number
  /** A no-card prompt that a good answer handles with attach (an image to post). */
  attachedInstead?: boolean
}

const LOCAL_REF = /\b(?:src|href)\s*=\s*["'](?!https?:|data:|blob:|#|mailto:|tel:|javascript:)([^"']+)["']|url\(\s*["']?(?!https?:|data:|blob:|#)[^)"'\s]+/i
const PAGE_BACKGROUND = /(?:^|[}\s,>;])(?:html|body|:root)\s*(?:,\s*(?:html|body|:root)\s*)*\{[^}]*background(?:-color)?\s*:(?!\s*(?:transparent|none|inherit|initial|unset|var\(--sm-))/i

export function pageTraits(html: string): { selfContained: boolean; usesTheme: boolean; transparent: boolean } {
  return { selfContained: !LOCAL_REF.test(html), usesTheme: /var\(\s*--sm-/.test(html), transparent: !PAGE_BACKGROUND.test(html) }
}

export function score(prompt: EvalPrompt, calls: Call[], shownPage: string | undefined, sendWorks: boolean | undefined, finalText = ""): Score {
  const shows = calls.filter((c) => c.tool === "show_html" && !c.error)
  const previews = calls.filter((c) => c.tool === "preview_html" && !c.error)
  const checks = calls.filter((c) => (c.tool === "show_html" || c.tool === "preview_html") && !c.error).sort((a, b) => a.seq - b.seq)
  const shown = shows.filter((c) => c.shown)
  const realProblems = [...new Set(checks.filter((c) => !c.forced).flatMap((c) => (c.problems ?? []).map((p) => p.kind)))]
  const firstFlagged = checks.findIndex((c) => (c.problems ?? []).length > 0)
  const diagram = /```mermaid\b/.test(finalText)
  const s: Score = {
    // A no-card prompt fails only on a card the user would see: preview_html shows them nothing.
    toolUse: prompt.expect === "card" ? shown.length > 0 || (!!prompt.orDiagram && diagram) : shows.length === 0,
    shows: shows.length,
    previews: previews.length,
    ...(previews.length ? { previewedFirst: previews[0]!.seq < (shows[0]?.seq ?? Infinity) } : {}),
    ...(prompt.orDiagram ? { diagram } : {}),
    realProblems,
    unpromptedSends: checks.reduce((n, c) => n + (c.unpromptedSends?.length ?? 0), 0),
  }
  const firstReal = checks.find((c) => !c.forced)
  if (firstReal) s.firstClean = (firstReal.problems ?? []).length === 0
  if (firstFlagged >= 0) {
    // Repaired: a later show_html was shown, and it is a different page from the one that was flagged.
    const flaggedHash = checks[firstFlagged]!.hash
    const later = checks.slice(firstFlagged + 1)
    s.repaired = later.some((c) => c.tool === "show_html" && c.shown && (!flaggedHash || c.hash !== flaggedHash))
    const firstShown = shows.findIndex((c) => c.shown)
    if (firstShown >= 0) s.tries = firstShown + 1
  }
  if (shownPage !== undefined) {
    Object.assign(s, pageTraits(shownPage))
    // The check measured what the page paints behind itself; better than reading the CSS.
    const last = shown.at(-1)
    if (last?.checked) s.transparent = !last.background
  }
  if (prompt.send) s.sendWorks = sendWorks ?? false
  if (prompt.instead === "attach") {
    s.attachedInstead = calls.some((c) => c.tool === "attach" && (c.files ?? []).some((f) => /\.(png|jpe?g|webp|gif|svg)$/i.test(f)))
  }
  return s
}

export interface Result {
  runId: string
  agent: string
  model: string
  arm: string
  prompt: string
  sample: number
  expect: EvalPrompt["expect"]
  score: Score
  usage: Usage
  finalText: string
  refusedTools: string[]
  exitCode: number | null
  timedOut: boolean
  ms: number
  error?: string
}
