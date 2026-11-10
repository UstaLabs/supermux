import { expect, test } from "bun:test"
import { SHOW_HTML_DESCRIPTION, interactiveHtmlInstructions } from "./description"

// The evals (evals/show-html/RESULTS.md) chose these words: wording 1, variant C's check line, and one more sentence for
// the prompts every model answered in prose (pasted numbers, a comparison of options).
test("the tool says when, how, how the user answers, and what the result means", () => {
  expect(SHOW_HTML_DESCRIPTION).toContain("Show the user a live HTML page as a card in the chat")
  expect(SHOW_HTML_DESCRIPTION).toContain("Not for an answer that is a fact or a sentence")
  expect(SHOW_HTML_DESCRIPTION).toContain("supermux.send(text)")
  expect(SHOW_HTML_DESCRIPTION).toContain("If it lists problems, the page was not shown")
  expect(SHOW_HTML_DESCRIPTION).not.toContain("preview_html")
})

test("numbers the user pasted, and a comparison of options, are a card", () => {
  for (const text of [SHOW_HTML_DESCRIPTION, interactiveHtmlInstructions()]) {
    expect(text).toMatch(/numbers the user gives you/)
    expect(text).toMatch(/compar/i)
  }
})

test("the instructions end with variant C's check", () => {
  expect(interactiveHtmlInstructions()).toContain("## Interactive HTML")
  expect(interactiveHtmlInstructions()).toContain("If it lists problems, the user saw nothing: fix the page and call show_html again.")
})
