// The arms under test: a tool design (A, B, C) × an instruction wording (1, 2). Each arm is the tool list the stub
// server serves and the text the runner puts where supermux puts its environment prompt.
export type Variant = "A" | "B" | "C"
export type Wording = "1" | "2"
export type Arm = `${Variant}${Wording}`
export const ARMS: Arm[] = ["A1", "A2", "B1", "B2", "C1", "C2"]

export interface ToolSpec { name: string; description: string; inputSchema: Record<string, unknown> }

// The two wordings carry the same facts and the same example; 1 says them as terse rules, 2 with reasons.
const EXAMPLE = `<style>.bar{height:14px;border-radius:4px;background:var(--sm-chart-1)}</style>
<div class="bar" style="width:62%"></div>
<button onclick="supermux.send('Use B')">Use B</button>`
const VARS = "--sm-fg, --sm-muted-fg, --sm-card, --sm-border, --sm-muted, --sm-primary, --sm-chart-1 to --sm-chart-5"

const PAGE_RULES_1 = `One self-contained document: inline CSS and JS; libraries from public CDNs are fine; no local files, no localhost, no storage (localStorage, cookies). Colours from the theme variables (${VARS}); no background on html or body. Fluid width (about 390 px on a phone, 720 px on a desktop); charts at a fixed pixel height; no vh units or height:100% on html or body. Example:
${EXAMPLE}`

const PAGE_RULES_2 = `The page renders in a sandboxed frame inside the chat, on desktops and phones, in the user's light or dark theme.
- Keep it one self-contained document, with inline CSS and JS. Libraries from public CDNs load fine; local files, localhost and storage (localStorage, cookies) don't, because the frame can't reach them.
- Take colours from the theme variables (${VARS}) and leave html and body without a background, so the page matches the app in either theme.
- Keep the width fluid, about 390 px on a phone and 720 px on a desktop. Give charts a fixed pixel height, and don't use vh units or height:100% on html or body: the card grows to the page's height.
- Example:
${EXAMPLE}`

const SEND_1 = "A <button> that calls supermux.send(text) sends the user's choice to you as their message. Only a real click on a button sends; a clickable div doesn't."
const SEND_2 = "To let the user answer you, give the page a <button> that calls supermux.send(text): their click sends that text to you as their message. " +
  "Only a real click on a button sends, so a clickable div won't work."

const RESULT_A = "The result says whether the page was shown. If it lists problems, the page was not shown."
const RESULT_B = "show_html checks the page again: if it lists problems, the page was not shown."

const html = { type: "string", description: "The page: a whole HTML document or a fragment." }
const file = { type: "string", description: "Or a path to an .html file." }

function showHtml(variant: Variant, wording: Wording): ToolSpec {
  const what = "Show the user a live HTML page as a card in the chat: a chart, a comparison, a small interactive tool, a mockup."
  // Both wordings say the same things (when, when not, how, send, the result); 1 as terse rules, 2 with reasons.
  const when = wording === "1"
    ? "Use it when a visual or interactive result explains better than text, asked for or not. Not for an answer that is a fact or a sentence, not for code work, not for an image to post (make a PNG and attach it)."
    : "Use it when the result is easier to see or try than to read, whether or not the user asked for a visual. Keep an answer that is " +
      "a fact or a sentence, and code work, in plain text: a card there only slows them down. An image to post is a PNG you make " +
      "and attach, because a card can't leave the chat."
  const before = variant === "B" ? " Call preview_html first and fix what it reports." : ""
  return {
    name: "show_html",
    description: [what, when + before, wording === "1" ? PAGE_RULES_1 : PAGE_RULES_2, wording === "1" ? SEND_1 : SEND_2,
      variant === "B" ? RESULT_B : RESULT_A].join("\n\n"),
    inputSchema: {
      type: "object",
      properties: {
        title: { type: "string", description: "The card's title, 1–80 characters." },
        html, file,
        height: { type: "number", description: "Height in px for the first paint, if you know it (80–2000)." },
      },
      required: ["title"],
    },
  }
}

const PREVIEW: ToolSpec = {
  name: "preview_html",
  description: "Check an HTML page before you show it. Returns the problems a card would have (script errors, a blank page, overflow " +
    "at phone width, a height that grows forever) and screenshots at phone and desktop width. Only you see this; the user sees " +
    "nothing until you call show_html.",
  inputSchema: { type: "object", properties: { html, file } },
}

const ATTACH: ToolSpec = {
  name: "attach",
  description: "Send file attachments (image, video, recording, document, etc.) to the user. REQUIRED: files[] with at least one " +
    "local filesystem path; text is an optional caption. Your normal assistant output is already relayed to the user — do not use " +
    "this for plain text.",
  inputSchema: { type: "object", properties: { files: { type: "array", items: { type: "string" } }, text: { type: "string" } }, required: ["files"] },
}

export function toolsFor(arm: Arm): ToolSpec[] {
  const [variant, wording] = [arm[0] as Variant, arm[1] as Wording]
  return variant === "B" ? [PREVIEW, showHtml(variant, wording), ATTACH] : [showHtml(variant, wording), ATTACH]
}

/** supermux's own "Diagrams" section (prompts/environment.md), which every agent already has. */
const DIAGRAMS = "## Diagrams\n\nsupermux renders mermaid: put a diagram in a fenced code block tagged `mermaid` and the app draws it " +
  "(flowcharts, sequence, class, state, ER, gantt, mindmaps and more). Use one when a picture explains a flow or structure better than prose.\n"

/** The "Interactive HTML" section supermux would put in the environment prompt for this arm, after "Diagrams". */
export function instructionsFor(arm: Arm): string {
  const [variant, wording] = [arm[0] as Variant, arm[1] as Wording]
  const check = variant === "C"
    ? "\n\nBefore you finish: read show_html's result. If it lists problems, the user saw nothing: fix the page and call show_html again."
    : variant === "B" ? "\n\nCheck a page with preview_html, fix what it reports, then show it with show_html." : ""
  const body = wording === "1"
    ? "The show_html tool shows the user a live HTML page as a card in the chat. Use it when a visual or interactive result " +
      "explains better than text, asked for or not. Not for an answer that is a fact or a sentence, not for code work, not for an " +
      "image to post (make a PNG and attach it). One self-contained page, the --sm-* theme variables, no page background, fluid " +
      "down to phone width. A <button> calling supermux.send(text) lets the user answer you."
    : "The show_html tool puts a live page in the chat as a card. Reach for it when the result is easier to see or try than to read, " +
      "whether or not the user asked for a visual. Keep plain text for an answer that is a fact or a sentence, and for code work: a " +
      "card there only slows them down. An image to post is a PNG you make and attach, because a card can't leave the chat.\n" +
      "Cards render on phones too, so keep the page self-contained and fluid, use the --sm-* theme variables and leave the page " +
      "without a background. A <button> calling supermux.send(text) lets the user answer you with a click."
  return `${DIAGRAMS}\n## Interactive HTML\n\n${body}${check}\n`
}
