import { expect, test } from "bun:test"
import { pageTraits, score, type Call } from "./score"
import { PROMPTS } from "./prompts"

const prompt = (id: string) => PROMPTS.find((p) => p.id === id)!
const show = (seq: number, o: Partial<Call>): Call => ({ seq, tool: "show_html", page: `pages/${seq}.html`, checked: true, problems: [], ...o })

test("a card prompt passes only with a shown card; a no-card prompt only with no page at all", () => {
  expect(score(prompt("01-chart"), [show(1, { shown: true })], "<p>x</p>", undefined).toolUse).toBe(true)
  expect(score(prompt("01-chart"), [show(1, { shown: false, problems: [{ kind: "blank" }] })], undefined, undefined).toolUse).toBe(false)
  expect(score(prompt("11-fact"), [], undefined, undefined).toolUse).toBe(true)
  // preview_html shows the user nothing, so it doesn't fail a no-card prompt; a shown card does.
  expect(score(prompt("11-fact"), [{ seq: 1, tool: "preview_html", checked: true, problems: [] }], undefined, undefined).toolUse).toBe(true)
  expect(score(prompt("11-fact"), [show(1, { shown: true })], "<p>x</p>", undefined).toolUse).toBe(false)
})

test("a forced problem, then a shown page: repaired in two tries, and the forced report isn't counted against the page", () => {
  const s = score(prompt("18-repair-script"), [
    show(1, { shown: false, forced: true, problems: [{ kind: "script-error", message: "TypeError" }] }),
    show(2, { shown: true }),
  ], "<p>x</p>", undefined)
  expect(s).toMatchObject({ repaired: true, tries: 2, firstClean: true, realProblems: [] })
})

test("re-sending the same page after a problem is not a repair", () => {
  const s = score(prompt("17-repair-overflow"), [
    show(1, { shown: false, forced: true, hash: "aaa", problems: [{ kind: "overflow", width: 612 }] }),
    show(2, { shown: true, hash: "aaa" }),
  ], "<p>x</p>", undefined)
  expect(s.repaired).toBe(false)
})

test("a mermaid diagram answers the timeline as well as a card does", () => {
  expect(score(prompt("09-timeline"), [], undefined, undefined, "Here:\n```mermaid\ntimeline\n2019 : founded\n```").toolUse).toBe(true)
  expect(score(prompt("01-chart"), [], undefined, undefined, "```mermaid\npie\n```").toolUse).toBe(false)
})

test("the measured background beats reading the CSS", () => {
  const s = score(prompt("01-chart"), [show(1, { shown: true, background: "rgb(0, 0, 0)" })], "<div style='background:#000'>", undefined)
  expect(s.transparent).toBe(false)
})

test("a real problem with no re-call is not repaired", () => {
  const s = score(prompt("02-compare"), [show(1, { shown: false, problems: [{ kind: "overflow", width: 700 }] })], undefined, undefined)
  expect(s).toMatchObject({ repaired: false, firstClean: false, realProblems: ["overflow"] })
})

test("send and attach-instead are scored only where the prompt asks", () => {
  expect(score(prompt("05-palette"), [show(1, { shown: true })], "<p>x</p>", false).sendWorks).toBe(false)
  expect(score(prompt("01-chart"), [show(1, { shown: true })], "<p>x</p>", undefined).sendWorks).toBeUndefined()
  expect(score(prompt("14-image-to-post"), [{ seq: 1, tool: "attach", files: ["/w/dark-mode.png"] }], undefined, undefined))
    .toMatchObject({ toolUse: true, attachedInstead: true })
})

test("page traits: local files, theme variables, a page background", () => {
  expect(pageTraits(`<img src="chart.png">`).selfContained).toBe(false)
  expect(pageTraits(`<style>.hero{background:url(hero.jpg)}</style>`).selfContained).toBe(false)
  expect(pageTraits(`<script src="https://cdn.jsdelivr.net/npm/chart.js"></script><img src="data:image/png;base64,AA">`).selfContained).toBe(true)
  expect(pageTraits(`<div style="color:var(--sm-fg)">`).usesTheme).toBe(true)
  expect(pageTraits(`<style>body { background: #000; color: #fff }</style>`).transparent).toBe(false)
  expect(pageTraits(`<style>html, body { background: transparent }</style>`).transparent).toBe(true)
  expect(pageTraits(`<style>.card { background: #111 }</style>`).transparent).toBe(true)
})
