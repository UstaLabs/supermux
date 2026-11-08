// The arms under test: a tool design (A, B, C) × an instruction wording (1, 2). Each arm is the tool list the stub
// server serves and the text the runner puts where supermux puts its environment prompt.
export type Variant = "A" | "B" | "C"
export type Wording = "1" | "2"
export type Arm = `${Variant}${Wording}`
export const ARMS: Arm[] = ["A1", "A2", "B1", "B2", "C1", "C2"]

export interface ToolSpec { name: string; description: string; inputSchema: Record<string, unknown> }

const PAGE_RULES_1 = "One self-contained document: inline CSS and JS; libraries from public CDNs are fine; no local files. " +
  "Style with the theme variables (var(--sm-fg), --sm-muted-fg, --sm-card, --sm-border, --sm-primary, --sm-chart-1 to --sm-chart-5) " +
  "and leave the page background transparent. Fluid width: it is about 390 px wide on a phone and 720 px on a desktop. No vh units."

const PAGE_RULES_2 = `The page renders in a sandboxed frame inside the chat, on desktops and phones, in the user's light or dark theme.
- One self-contained document: inline CSS and JS. Libraries from public CDNs load fine; local files and localhost don't.
- Colours come from the theme variables on :root, so the page matches the app: --sm-fg, --sm-muted-fg, --sm-card,
  --sm-border, --sm-muted, --sm-primary, --sm-chart-1 … --sm-chart-5. Leave html and body without a background.
- Width is fluid: about 390 px on a phone, 720 px on a desktop. Give charts a fixed pixel height. Don't use vh units or
  height:100% on html/body: the card grows to the page's height.
- Example:
  <style>.bar{height:14px;border-radius:4px;background:var(--sm-chart-1)}</style>
  <div class="bar" style="width:62%"></div>
  <button onclick="supermux.send('Use option B')">Use B</button>`

const SEND_1 = "A button in the page can send the user's choice back to you with supermux.send(text); it works only on their click."
const SEND_2 = "If the user should pick something, give the page a button that calls supermux.send(text): their click sends that text " +
  "to you as their message. It only works from a real click."

const RESULT_A = "The result says whether the page was shown. If it lists problems, the page was not shown."
const RESULT_B = "show_html checks the page again: if it lists problems, the page was not shown."

const html = { type: "string", description: "The page: a whole HTML document or a fragment." }
const file = { type: "string", description: "Or a path to an .html file." }

function showHtml(variant: Variant, wording: Wording): ToolSpec {
  const what = "Show the user a live HTML page as a card in the chat: charts, comparisons, small interactive tools, UI mockups."
  const when = wording === "1"
    ? "Use it when a visual or interactive result explains better than text. Not for plain answers, code changes, or an image to post (attach a PNG)."
    : "Use it when seeing or trying something beats reading about it: numbers to compare, options to pick from, a value to tune, a " +
      "layout to look at. Also without being asked, when the user pastes data and asks a question a chart answers. Don't use it for a " +
      "plain answer, for code work that only mentions charts, or for an image they want to post (make a PNG and attach it)."
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

/** The "Interactive HTML" section supermux would put in the environment prompt for this arm. */
export function instructionsFor(arm: Arm): string {
  const [variant, wording] = [arm[0] as Variant, arm[1] as Wording]
  const check = variant === "C"
    ? "\n\nBefore you finish: read show_html's result. If it lists problems, the user saw nothing: fix the page and call show_html again."
    : variant === "B" ? "\n\nCheck a page with preview_html, fix what it reports, then show it with show_html." : ""
  const body = wording === "1"
    ? "You can show the user a live HTML page in the chat with the show_html tool. Use it when a chart, a comparison, a small " +
      "calculator or a mockup explains better than text, including when the user pastes numbers and asks a question a chart " +
      "answers. Don't use it for plain answers, for code work, or for an image to post. Keep the page self-contained, use the " +
      "theme variables with a transparent background, and keep it fluid down to phone width."
    : "The show_html tool puts a live page in the chat as a card. Reach for it when seeing beats reading:\n" +
      "- numbers someone wants to compare or understand (also when they paste data and ask a question a chart would answer);\n" +
      "- options to pick from, values to tune, a layout or a flow to look at; a small tool they'll use once.\n" +
      "Answer in plain text instead when the answer is a fact or a sentence, when they ask you to just tell them, or when the " +
      "work is code. An image to post is a PNG you attach, not a card.\n" +
      "Pages render on phones too: fluid width, the --sm-* theme variables, no page background. A button with " +
      "supermux.send(text) lets them answer you with a click."
  return `## Interactive HTML\n\n${body}${check}\n`
}
