// What agents are told about show_html (#104): the tool's description and the "Interactive HTML" section of the
// environment prompt. The evals chose these words (evals/show-html/RESULTS.md): wording 1 (terse rules), variant C's
// "check the result" line, and DATA, for the prompts every model answered in prose: numbers the user pasted, and a
// comparison of options.

const VARS = "--sm-fg, --sm-muted-fg, --sm-card, --sm-border, --sm-muted, --sm-primary, --sm-chart-1 to --sm-chart-5"

const EXAMPLE = `<style>.bar{height:14px;border-radius:4px;background:var(--sm-chart-1)}</style>
<div class="bar" style="width:62%"></div>
<button onclick="supermux.send('Use B')">Use B</button>`

const DATA =
  "When the numbers the user gives you (a table, a series, figures by month, region or release) or a comparison of options " +
  "have a shape worth seeing, show them as a card and answer in a sentence or two beside it."

export const SHOW_HTML_DESCRIPTION = [
  "Show the user a live HTML page as a card in the chat: a chart, a comparison, a small interactive tool, a mockup.",
  "Use it when a visual or interactive result explains better than text, asked for or not. " + DATA + " Not for an answer " +
    "that is a fact or a sentence, not for code work, not for an image to post (make a PNG and attach it).",
  "One self-contained document: inline CSS and JS; libraries from public CDNs are fine; no local files, no localhost, no " +
    `storage (localStorage, cookies). Colours from the theme variables (${VARS}); no background on html or body. Fluid ` +
    "width (390 px phone, 720 px desktop); charts get a fixed px height; no vh units or height:100% on html or body.\n" +
    EXAMPLE,
  "A <button> that calls supermux.send(text) sends the user's choice to you as their message. Only a real click on a " +
    "button sends; a clickable div doesn't.",
  "The result says whether the page was shown. If it lists problems, the page was not shown.",
].join("\n\n")

/** The environment prompt's "Interactive HTML" section, after "Diagrams". */
export function interactiveHtmlInstructions(): string {
  return "## Interactive HTML\n\n" +
    "The show_html tool shows the user a live HTML page as a card in the chat. Use it when a visual or interactive result " +
    "explains better than text, asked for or not. " + DATA + " Not for an answer that is a fact or a sentence, not for " +
    "code work, not for an image to post (make a PNG and attach it). One self-contained page, the --sm-* theme variables, " +
    "no page background, fluid down to phone width. A <button> calling supermux.send(text) lets the user answer you." +
    "\n\nBefore you finish: read show_html's result. If it lists problems, the user saw nothing: fix the page and call " +
    "show_html again.\n"
}
