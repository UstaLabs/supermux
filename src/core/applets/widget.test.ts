import { expect, test } from "bun:test"
import { actionPath, parseWidget, parseWidgetConfig, HOME_SIZES } from "./widget"

test("applet.json widget: a v1 path, the full form, and the mistakes an agent sees", () => {
  expect(parseWidgetConfig(undefined)).toEqual({ ok: true, config: undefined })
  expect(parseWidgetConfig("/widget.json")).toEqual({ ok: true, config: { data: "/widget.json", actions: {} } })
  expect(parseWidgetConfig("widget.json")).toMatchObject({ ok: false })
  expect(parseWidgetConfig({ data: "/widget.json", html: "/widget.html", accent: "#2f9e8c", actions: { tested: { path: "/api/issues/{id}/tested", title: "Tested" } } })).toEqual({
    ok: true,
    config: { data: "/widget.json", html: "/widget.html", accent: "#2f9e8c", actions: { tested: { title: "Tested", method: "POST", path: "/api/issues/{id}/tested", confirm: false } } },
  })
  expect(parseWidgetConfig({ html: "/w.html" })).toEqual({ ok: false, error: `"widget.data" is required (the path that answers the widget, e.g. /widget.json)` })
  expect(parseWidgetConfig({ data: "/w", accent: "teal" })).toMatchObject({ ok: false })
  expect(parseWidgetConfig({ data: "/w", actions: { Bad: { path: "/x" } } })).toMatchObject({ ok: false })
  expect(parseWidgetConfig({ data: "/w", actions: { go: { path: "/x", method: "GET" } } })).toMatchObject({ ok: false })
  expect(parseWidgetConfig({ data: "/w", actions: { archive: { path: "/x", confirm: true } } })).toMatchObject({ ok: true, config: { actions: { archive: { confirm: true, method: "POST" } } } })
})

test("an action path is filled from its args, encoded, and refused when one is missing", () => {
  expect(actionPath("/api/issues/{id}/tested", { id: 164 })).toBe("/api/issues/164/tested")
  expect(actionPath("/api/h/{id}", { id: "a b/c" })).toBe("/api/h/a%20b%2Fc")
  expect(actionPath("/api/h/{id}", {})).toBeNull()
  expect(actionPath("/api/h/{id}", { id: { x: 1 } })).toBeNull()
  expect(actionPath("/api/sync", {})).toBe("/api/sync")
})

test("v1 {value, label, lines} becomes a v2 document with every size, and keeps its summary", () => {
  const p = parseWidget({ value: 31, label: "open", lines: ["2 waiting for testing"] }, { title: "Issues" })!
  expect(p.summary).toEqual({ value: "31", label: "open", lines: ["2 waiting for testing"] })
  for (const k of HOME_SIZES) expect(p.doc.layouts[k].length).toBeGreaterThan(0)
  expect(p.doc.layouts.small).toEqual([{ type: "header", title: "Issues" }, { type: "stat", value: "31", label: "open" }])
  expect(p.doc.layouts.circular).toEqual({ text: "31", label: "open" })
  expect(p.doc.layouts.rectangular.lines).toEqual(["Issues", "31 open", "2 waiting for testing"])
  expect(p.doc.layouts.inline).toBe("31 open")
  expect(parseWidget({ other: 1 }, { title: "X" })).toBeNull()
  expect(parseWidget([1], { title: "X" })).toBeNull()
})

test("v2: sizes it leaves out borrow the nearest one; buttons need a declared action; pictures need a label", () => {
  const raw = {
    v: 2,
    href: "/#board",
    layouts: {
      small: [{ type: "header", title: "Issues" }, { type: "stat", value: "2", label: "to test", tone: "warn" }],
      medium: [{ type: "columns", w: [0.4, 0.6], cols: [
        [{ type: "stat", value: "2", label: "to test" }],
        [{ type: "list", title: "Waiting for you", rows: [
          { title: "#164 Desktop resize", sub: "bug", color: "#ff9500", href: "/#164", button: { title: "Tested", action: "tested", args: { id: 164 } } },
          { title: "#162 Cards", button: { title: "Nope", action: "unknown" } },
        ] }],
      ] }],
      large: [
        { type: "html", part: "board", height: 70, alt: { type: "chart", kind: "bars", points: [1, 2] } },
        { type: "html", part: "board", height: 70, label: "Board: 12 to do", caption: "Board", alt: { type: "chart", kind: "bars", points: [1, 2] } },
        { type: "mystery" },
      ],
      rectangular: { lines: ["Issues", "2 waiting", "x"], button: { title: "Tested #164", action: "tested", args: { id: 164 } } },
      inline: "2 to test",
    },
    timeline: [{ at: "2026-11-27T00:00:00+03:00", layouts: { small: [{ type: "stat", value: "0" }] } }, { at: "nope", layouts: {} }],
  }
  const p = parseWidget(raw, { title: "Issues", actions: ["tested"] })!
  expect(p.doc.href).toBe("/#board")
  expect(p.doc.layouts.wide).toBe(p.doc.layouts.medium)
  expect(p.doc.layouts.tall).toBe(p.doc.layouts.large)
  expect(p.doc.layouts.slim).toBe(p.doc.layouts.small)
  expect(p.doc.layouts.xlarge).toBe(p.doc.layouts.large)
  const list = (p.doc.layouts.medium[0] as any).cols[1][0]
  expect(list.rows[0].button).toEqual({ title: "Tested", action: "tested", args: { id: 164 } })
  expect(list.rows[1].button).toBeUndefined()
  expect(p.doc.layouts.large).toEqual([{ type: "html", part: "board", height: 70, label: "Board: 12 to do", caption: "Board", alt: { type: "chart", kind: "bars", points: [1, 2] } }])
  expect(p.doc.layouts.rectangular).toEqual({ lines: ["Issues", "2 waiting"], button: { title: "Tested #164", action: "tested", args: { id: 164 } } })
  expect(p.doc.layouts.circular).toEqual({ text: "2", label: "to test" })
  expect(p.doc.timeline).toEqual([{ at: "2026-11-26T21:00:00.000Z", layouts: { small: [{ type: "stat", value: "0" }] } }])
  expect(p.summary).toEqual({ value: "2", label: "to test", lines: ["#164 Desktop resize", "#162 Cards"] })
  expect(p.problems.some((x) => x.includes(`action "unknown"`))).toBe(true)
  expect(p.problems.some((x) => x.includes(`needs a "label"`))).toBe(true)
  expect(p.problems.some((x) => x.includes(`unknown block type "mystery"`))).toBe(true)
  expect(p.problems.some((x) => x.startsWith("timeline[1]"))).toBe(true)
})

test("an href or image never points at another site", () => {
  const p = parseWidget({ v: 2, href: "https://evil.example", layouts: { small: [
    { type: "list", rows: [{ title: "a", href: "javascript:alert(1)" }, { title: "b", href: "//evil" }, { title: "c", href: "/#c" }] },
    { type: "image", src: "https://evil.example/x.png" },
  ] } }, { title: "X" })!
  expect(p.doc.href).toBeUndefined()
  expect((p.doc.layouts.small[0] as any).rows.map((r: any) => r.href)).toEqual([undefined, undefined, "/#c"])
  expect(p.doc.layouts.small).toHaveLength(1)
})

test("the full-snapshot escape hatch is kept but flagged, and wants a label", () => {
  const p = parseWidget({ v: 2, snapshot: "full", layouts: {} }, { title: "Telemetry" })!
  expect(p.doc.snapshot).toBe("full")
  expect(p.problems.some((x) => x.includes("needs a \"label\""))).toBe(true)
  expect(p.problems.some((x) => x.includes("escape hatch"))).toBe(true)
  const q = parseWidget({ v: 2, snapshot: "full", label: "Telemetry: 5 daily active", layouts: { small: [{ type: "stat", value: "5" }] } }, { title: "Telemetry" })!
  expect(q.doc.label).toBe("Telemetry: 5 daily active")
  expect(parseWidget({ v: 2, layouts: {} }, { title: "X" })).toBeNull()
})

test("toggles, Lock Screen circular action, and size limits", () => {
  const p = parseWidget({ v: 2, layouts: {
    small: [{ type: "toggles", items: [
      { title: "Ride", on: false, action: "toggle", args: { id: "ride" } },
      { title: "Read", on: true, action: "toggle", args: { id: "read" } },
      { title: "Nope", action: "missing" },
    ] }, { type: "stat", value: "x".repeat(40) }],
    circular: { ring: 2, text: "2/4", label: "habits", action: { action: "toggle", args: { id: "ride" } } },
  } }, { title: "Habits", actions: ["toggle"] })!
  expect((p.doc.layouts.small[0] as any).items).toEqual([
    { title: "Ride", on: false, action: "toggle", args: { id: "ride" } },
    { title: "Read", on: true, action: "toggle", args: { id: "read" } },
  ])
  expect((p.doc.layouts.small[1] as any).value).toHaveLength(16)
  expect(p.doc.layouts.circular).toEqual({ text: "2/4", ring: 1, label: "habits", action: { action: "toggle", args: { id: "ride" } } })
  expect(parseWidget({ v: 2, layouts: { small: [{ type: "text", text: "x".repeat(60_000) }] } }, { title: "X" })).toBeNull()
})

test("a document with both forms keeps its own v1 summary for older apps", () => {
  const p = parseWidget({ value: 31, label: "waiting", lines: ["0 interviews"], v: 2, layouts: { small: [{ type: "stat", value: "31", label: "waiting to hear" }] } }, { title: "Job Hunt" })!
  expect(p.summary).toEqual({ value: "31", label: "waiting", lines: ["0 interviews"] })
  expect(p.doc.layouts.small).toEqual([{ type: "stat", value: "31", label: "waiting to hear" }])
})
