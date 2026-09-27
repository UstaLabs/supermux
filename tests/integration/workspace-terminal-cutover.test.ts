// The Plan 4 cutover, asserted against the tree rather than against a running program.
//
// Everything else in this suite tests BEHAVIOUR. This file tests the one thing behaviour cannot:
// that the replaced implementations are actually gone and that no target quietly kept one. A
// terminal that silently falls back is indistinguishable from a working one in every functional
// test — the fallback IS a working terminal, just not the one we shipped — so the only way to
// catch a half-cutover is to look at what the tree declares.
//
// TWO KINDS OF CHECK, DELIBERATELY SEPARATED:
//
//  1. HARD GATES (`expect`): the workspace terminal manager no longer reaches for tmux, every
//     host binds the ONE shared factory, the four retired renderers are gone, and so are their
//     dependency declarations.
//
//  2. A REPORT (printed, not asserted): agent terminals still ride tmux, and will until the
//     Claude native-terminal retirement lands. Asserting "the word tmux does not appear" would be
//     a lie about this repo — it appears in the agent session backend, in the portable-binary
//     staging, in the preflight check and all over the design history. So the scan below prints
//     what it found and where, and only FAILS when a tmux consumer turns up somewhere the
//     workspace path can reach.
import { describe, expect, test } from "bun:test"
import { existsSync, readFileSync, readdirSync, statSync } from "fs"
import { join, relative } from "path"

const ROOT = join(import.meta.dir, "..", "..")
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8")
const here = (rel: string) => existsSync(join(ROOT, rel))

/** Every file under `rel` matching `pred`, recursively, as repo-relative paths. */
function walk(rel: string, pred: (path: string) => boolean): string[] {
  const out: string[] = []
  const visit = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      if (entry === "node_modules" || entry === "build" || entry === ".git") continue
      const full = join(dir, entry)
      if (statSync(full).isDirectory()) visit(full)
      else if (pred(full)) out.push(relative(ROOT, full))
    }
  }
  visit(join(ROOT, rel))
  return out.sort()
}

/**
 * Source with its comments removed.
 *
 * The distinction this whole file rests on: a comment that SAYS "tmux" is documentation (often
 * the documentation of why we left it), and code that says "tmux" is a dependency. Naive string
 * matching cannot tell them apart, and a gate that cannot tell them apart is one that either
 * passes forever or has to be suppressed.
 */
/**
 * Does this source actually name tmux?
 *
 * `includes("tmux")` also matches `agentmux` — the project's own former name, which still appears
 * in the two migration scripts that exist to get users off it. Both were being reported as
 * surviving tmux consumers, and both were copied into the runbook's remainder table as such.
 * Neither mentions tmux at all.
 */
function mentionsTmux(source: string): boolean {
  return source.replace(/agentmux/gi, "").includes("tmux")
}

function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^[ \t]*\/\/.*$/gm, "")
    .replace(/([^:])\/\/.*$/gm, "$1")
}

describe("workspace terminal cutover: the manager left tmux", () => {
  test("the workspace tmux module and its tests are gone", () => {
    expect(here("src/core/terminal/tmux-term.ts")).toBe(false)
    expect(here("src/core/terminal/tmux-term.test.ts")).toBe(false)
  })

  test("nothing imports tmux-term any more", () => {
    const sources = [...walk("src", p => p.endsWith(".ts")), ...walk("tests", p => p.endsWith(".ts"))]
    const importers = sources.filter(rel => rel !== relative(ROOT, join(ROOT, "tests/integration/workspace-terminal-cutover.test.ts")))
      .filter(rel => read(rel).includes("tmux-term\""))
    expect(importers).toEqual([])
  })

  test("the workspace tmux socket override is read nowhere", () => {
    // `MUX_TERM_TMUX_SOCKET` chose the `tmux -L <socket>` server workspace terminals lived on.
    // With the module gone the variable must be inert — a stray read would mean a second,
    // undeclared backend is still reachable through the environment.
    const sources = [...walk("src", p => p.endsWith(".ts")), ...walk("scripts", p => p.endsWith(".ts") || p.endsWith(".sh"))]
    const readers = sources.filter(rel => code(read(rel)).includes("MUX_TERM_TMUX_SOCKET"))
    expect(readers).toEqual([])
  })

  test("the manager's only tmux import is the agent one, and workspace work goes to the backend", () => {
    const manager = read("src/core/terminal/manager.ts")
    // flatMap, not map: with `noUncheckedIndexedAccess` a capture group is `string | undefined`,
    // and `imports.filter(spec => spec.includes(...))` does not typecheck against that.
    const imports = [...code(manager).matchAll(/from\s+"(\.[^"]+)"/g)].flatMap(m => (m[1] ? [m[1]] : []))
    expect(imports.filter(spec => spec.includes("tmux"))).toEqual(["./agent-tmux"])

    // The workspace path is the backend's, with no branch that can pick something else: the only
    // backend choice left is the PLATFORM's (zmx on POSIX, sessiond on Windows).
    expect(manager).toContain("new ZmxWorkspaceBackend(")
    expect(manager).toContain("new SessiondWorkspaceBackend(")
    expect(code(manager)).not.toContain("createTermTmux")
  })

  test("no workspace-side module executes tmux", () => {
    // Comments about tmux are history and are welcome. Code is a dependency: after the cutover
    // only the AGENT viewer path may have any, and only in these two files.
    const agentOwned = new Set(["src/core/terminal/agent-tmux.ts", "src/core/terminal/manager.ts"])
    const offenders = walk("src/core/terminal", p => p.endsWith(".ts") && !p.endsWith(".test.ts"))
      .filter(rel => !agentOwned.has(rel))
      .filter(rel => code(read(rel)).includes("tmux"))
    expect(offenders).toEqual([])
  })
})

describe("workspace terminal cutover: every host binds the shared factory", () => {
  // One entry per host actual behind `Platform.terminalView()`. `SharedTerminal` is the single
  // `GhosttyTerminalViewFactory` in `:ui`; a host that bound anything else — or fell back to
  // `UnavailableTerminalViewFactory` — would still compile and still draw something.
  const hosts: Array<[string, string]> = [
    ["android", "apps/android/src/main/kotlin/dev/supermux/android/platform/AndroidPlatform.kt"],
    ["desktop", "apps/desktop/src/main/kotlin/dev/supermux/desktop/platform/DesktopPlatform.kt"],
    ["ios", "apps/ios/src/iosMain/kotlin/dev/supermux/ios/IosPlatform.kt"],
    ["web", "apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebPlatform.kt"],
  ]

  for (const [host, path] of hosts) {
    test(`${host} returns SharedTerminal from terminalView()`, () => {
      const source = read(path)
      expect(source).toContain("import dev.supermux.ui.terminal.SharedTerminal")
      expect(source).toMatch(/override fun terminalView\(\)\s*:\s*TerminalViewFactory\s*=\s*SharedTerminal/)
      expect(source).not.toContain("UnavailableTerminalViewFactory")
    })
  }

  test("`:ui` still has exactly one factory implementation to bind", () => {
    const factory = read("apps/ui/src/commonMain/kotlin/dev/supermux/ui/terminal/GhosttyTerminalViewFactory.kt")
    expect(factory).toContain("val SharedTerminal")
  })

  test("the four retired renderers are gone", () => {
    for (const path of [
      "apps/android/src/main/kotlin/dev/supermux/android/terminal",
      "apps/desktop/src/main/kotlin/dev/supermux/desktop/terminal",
      "apps/web/src/wasmJsMain/kotlin/dev/supermux/web/terminal",
      "apps/ios/src/iosMain/kotlin/dev/supermux/ios/IosTerminal.kt",
      "apps/ios/src/iosMain/kotlin/dev/supermux/ios/IosTerminalViewFactory.kt",
      "apps/iosApp/Supermux/Terminal",
    ]) {
      expect([path, here(path)]).toEqual([path, false])
    }
  })
})

describe("workspace terminal cutover: the replaced dependencies are gone", () => {
  test("the version catalog declares neither retired terminal library", () => {
    const catalog = read("apps/gradle/libs.versions.toml")
    expect(catalog).not.toContain("termlib")
    expect(catalog).not.toContain("jediterm")
  })

  test("no Gradle module depends on them", () => {
    for (const path of walk("apps", p => p.endsWith("build.gradle.kts"))) {
      const source = read(path)
      expect([path, source.includes("libs.termlib")]).toEqual([path, false])
      expect([path, source.includes("libs.jediterm")]).toEqual([path, false])
    }
  })

  test("the browser terminal declares no npm renderer and links no stylesheet", () => {
    const web = read("apps/web/build.gradle.kts")
    for (const pkg of ["@xterm/xterm", "@xterm/addon-fit", "@xterm/addon-webgl"]) {
      expect([pkg, web.includes(pkg)]).toEqual([pkg, false])
    }
    expect(web).not.toContain("xterm.css")
    expect(read("apps/web/src/wasmJsMain/resources/index.html")).not.toContain("xterm.css")
  })

  test("the iOS project declares no SwiftPM package", () => {
    const project = read("apps/iosApp/project.yml")
    expect(project).not.toContain("SwiftTerm:")
    expect(project).not.toContain("- package:")
  })

  test("the Windows sessiond screen model keeps @xterm/headless", () => {
    // PRESERVED ON PURPOSE. It is the server-side terminal model behind ConPTY, not a renderer;
    // the name is the only thing it has in common with what this cutover deleted.
    const pkg = JSON.parse(read("package.json")) as { dependencies?: Record<string, string> }
    expect(pkg.dependencies?.["@xterm/headless"]).toBeTruthy()
    expect(read("src/core/sessiond/screen.ts")).toContain('from "@xterm/headless"')
  })
})

describe("workspace terminal cutover: what still uses tmux, and why", () => {
  test("reports the surviving agent/backend consumers instead of pretending they are gone", () => {
    // NOT an assertion that the list is empty, and not an assertion that it is non-empty either:
    // agent terminals keep tmux until the Claude native-terminal retirement lands (a separate
    // workstream), and when it does this report should simply shrink. What it exists for is to
    // make the remaining surface VISIBLE in the cutover's own run, so "we removed tmux" is never
    // read as more than it is.
    const consumers = [
      ...walk("src", p => p.endsWith(".ts") && !p.endsWith(".test.ts")),
      ...walk("scripts", p => p.endsWith(".ts") || p.endsWith(".sh")),
    ].filter(rel => mentionsTmux(code(read(rel))))

    // NAMED FILES, NOT PREFIXES. Directory prefixes made two of these groups catch-alls —
    // `src/core/terminal/` and `scripts/` swallowed anything under them — so the assertion below
    // could not distinguish "the remainder we know about" from "a new consumer in a familiar
    // directory". A workspace tmux module re-introduced beside `agent-tmux.ts` would have been
    // filed under "agent terminal viewer" and reported as expected; a script that shells out to
    // tmux would have been filed under "portable binary staging" whatever it actually did.
    //
    // So the remainder is enumerated. Adding a tmux consumer now means adding its path here, in a
    // diff a reviewer reads, with a group name that has to be true of it. That is the whole point:
    // the list is the decision, and the gate only checks that nothing bypassed it.
    const KNOWN_CONSUMERS: ReadonlyArray<readonly [string, readonly string[]]> = [
      ["agent session backend", [
        "src/core/runtime/index.ts",
        "src/core/runtime/tmux-backend.ts",
        "src/core/session-manager/manager.ts",
        "src/core/session-manager/registry.ts",
        "src/core/session-manager/session-store.ts",
        "src/core/session-manager/spawn-helper.ts",
        "src/core/session-manager/spawn-registration.ts",
        "src/core/session-manager/supervisor.ts",
        "src/core/session-manager/tmux.ts",
        "src/core/session-manager/types.ts",
        "src/core/session-manager/window-id.ts",
      ]],
      // EXACTLY TWO FILES, and the "no workspace-side module executes tmux" gate above says the
      // same thing from the other direction. Both, on purpose: that one catches a new module in
      // this directory, this one catches `agent-tmux.ts` growing a workspace caller.
      ["agent terminal viewer", [
        "src/core/terminal/agent-tmux.ts",
        "src/core/terminal/manager.ts",
      ]],
      // Claude's window naming: an agent worker is a window in the agent's own tmux session.
      ["agent orchestration", ["src/core/agents/claude/session.ts"]],
      // The `tmux_target` / `tmux_window_id` columns on the session record, and the migration
      // that added them. Historical schema, not a live dependency of the workspace path.
      ["agent session records", ["src/core/storage/migrations/index.ts"]],
      // main.ts's preflight and the spawn path: the broker telling a user tmux is missing.
      ["broker wiring", ["src/main.ts", "src/shared/preflight.ts"]],
      ["portable binary staging", [
        "scripts/build-portable-tmux.sh",
        "scripts/stage-desktop-binaries.sh",
      ]],
      // Development and test scaffolding — a stubbed `tmux` on a fixture PATH, a seeded
      // `tmux_target` column, a comment about not sharing the live broker's panes. These used to
      // be filed under "portable binary staging" because they live in `scripts/`, which is not
      // what any of them are.
      ["dev and test scaffolding", [
        "scripts/shadow-broker.sh",
        "scripts/test-broker-seed.ts",
        "scripts/test-broker.sh",
      ]],
    ]
    const groupOf = new Map<string, string>()
    for (const [name, files] of KNOWN_CONSUMERS) for (const rel of files) groupOf.set(rel, name)
    const group = (rel: string) => groupOf.get(rel) ?? `UNRECOGNISED tmux consumer: ${rel}`

    const byGroup = new Map<string, string[]>()
    for (const rel of consumers) byGroup.set(group(rel), [...(byGroup.get(group(rel)) ?? []), rel])

    // A listed file that no longer mentions tmux is PROGRESS, not a failure — that is what the
    // agent retirement will look like, file by file. It is still drift, so it is reported.
    const retired = [...groupOf.keys()].filter(rel => !consumers.includes(rel)).sort()
    if (retired.length > 0) {
      console.log(
        `NOTE: ${retired.length} file(s) in KNOWN_CONSUMERS no longer mention tmux — remove them ` +
        `from the list and regenerate the runbook table:\n${retired.map(f => `    ${f}`).join("\n")}`,
      )
    }

    const sorted = [...byGroup.entries()].sort(([a], [b]) => a.localeCompare(b))
    const lines = sorted
      .map(([name, files]) => `  ${name} (${files.length}):\n${files.map(f => `    ${f}`).join("\n")}`)
    console.log(
      `tmux remains in ${consumers.length} non-test source file(s), all agent-side:\n${lines.join("\n")}\n` +
        "  (workspace terminals are zmx/sessiond; the gates above are what enforce that)",
    )

    // THE SAME FACTS, READY TO PASTE. `docs/terminal-cutover.md` §1.4 carries this table for the
    // operator, and a hand-maintained copy of a generated list is a copy that drifts — the first
    // version of it disagreed with this run on three of six groups (wrong counts, two files that
    // do not exist, one that does and was missing). So the gate emits the table it would have to
    // be checked against, between markers a `sed` range can lift out. The doc says how.
    console.log(
      ["<!-- BEGIN tmux-remainder — generated by tests/integration/workspace-terminal-cutover.test.ts -->",
        `<!-- ${consumers.length} non-test source files, at the revision this was run on -->`,
        "| group | files |",
        "|---|---|",
        ...sorted.map(([name, files]) =>
          `| ${name} (${files.length}) | ${files.map(f => `\`${f}\``).join("<br>")} |`),
        "<!-- END tmux-remainder -->"].join("\n"),
    )

    // The one thing worth asserting here: whatever remains is agent-side, in a place we already
    // knew about. A file outside every known group carries its own path as its group name, so
    // this fails with the offender in the diff rather than silently absorbing it.
    expect([...byGroup.keys()].sort()).toEqual(
      KNOWN_CONSUMERS.map(([name]) => name)
        .sort()
        .filter(g => byGroup.has(g)),
    )
  })
})
