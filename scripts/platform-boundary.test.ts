import { describe, expect, test } from "bun:test"
import { compare, countHits, RULES, scanText, type Counts } from "./platform-boundary"

const ts = (file: string, text: string) => scanText(file, text, RULES.filter(r => r.lang === "ts"))
const kt = (file: string, text: string) => scanText(file, text, RULES.filter(r => r.lang === "kt"))
const ids = (hits: { rule: string }[]) => hits.map(h => h.rule)

describe("TypeScript rules", () => {
  test("each pattern of an OS decision is flagged in feature code", () => {
    expect(ids(ts("src/a.ts", 'if (process.platform === "win32") {}'))).toEqual(["os-check"])
    expect(ids(ts("src/a.ts", "const h = process.env.HOME ?? homedir()"))).toEqual(["env-dirs"])
    expect(ids(ts("src/a.ts", 'const dir = "/tmp/x"'))).toEqual(["env-dirs"])
    expect(ids(ts("src/a.ts", 'import { spawn } from "node:child_process"'))).toEqual(["spawn"])
    expect(ids(ts("src/a.ts", "const p = Bun.spawn(argv)"))).toEqual(["spawn"])
    expect(ids(ts("src/a.ts", "symlinkSync(target, path)"))).toEqual(["links-perms"])
    expect(ids(ts("src/a.ts", "mkdirSync(d, { mode: 0o700 })"))).toEqual(["links-perms"])
    expect(ids(ts("src/a.ts", 'process.kill(-pid, "SIGTERM")'))).toEqual(["signals"])
    expect(ids(ts("src/a.ts", 'const leaf = path.split("/").pop()'))).toEqual(["path-shape"])
    expect(ids(ts("src/a.ts", 'if (p.startsWith(root + "/")) {}'))).toEqual(["path-shape"])
    // The same prefix with the host's separator: still exact case, and only one side resolved (card #75's folderReady).
    expect(ids(ts("src/a.ts", "const inside = target.startsWith(dir + sep)"))).toEqual(["path-shape"])
    expect(ids(ts("src/a.ts", "if (p.startsWith(root + path.sep)) {}"))).toEqual(["path-shape"])
    expect(ids(ts("src/a.ts", 'import { posix } from "path"'))).toEqual(["path-shape"])
    expect(ids(ts("src/a.ts", "const found = Bun.which(name) !== null"))).toEqual(["which-path"])
  })

  test("the runtime's own answers pass", () => {
    expect(ts("src/a.ts", 'symlinkSync(dir, link, "junction")')).toEqual([])
    expect(ts("src/a.ts", "const leaf = basename(p); const rel = relative(root, p)")).toEqual([])
    expect(ts("src/a.ts", "const dir = mkdtempSync(join(tmpdir(), 'x-'))")).toEqual([])
    expect(ts("src/a.ts", 'const dart = Bun.which("dart", { PATH: process.env.PATH })')).toEqual([])
  })

  test("allowed places pass", () => {
    expect(ts("packages/platform/src/os.ts", "export const os = process.platform")).toEqual([])
    expect(ts("src/core/process/launcher.ts", "Bun.spawn(argv)")).toEqual([])
    expect(ts("src/channels/web/index.ts", 'const parts = url.pathname.split("/")')).toEqual([])
  })

  test("comment lines are skipped", () => {
    expect(ts("src/a.ts", "// process.platform is decided elsewhere\n * split(\"/\") here would be wrong")).toEqual([])
  })
})

describe("Kotlin rules", () => {
  test("each pattern is flagged in feature code", () => {
    expect(ids(kt("apps/ui/src/commonMain/kotlin/A.kt", 'val leaf = path.substringAfterLast(\'/\')'))).toEqual(["host-path-kt"])
    expect(ids(kt("apps/desktop/src/main/kotlin/B.kt", 'val os = System.getProperty("os.name")'))).toEqual(["os-check-kt"])
    expect(ids(kt("apps/desktop/src/main/kotlin/B.kt", 'ProcessBuilder("cmd", "/c", "start")'))).toEqual(["spawn-kt"])
    expect(ids(kt("apps/desktop/src/main/kotlin/B.kt", 'val home = System.getProperty("user.home")'))).toEqual(["env-dirs-kt"])
    expect(ids(kt("apps/desktop/src/main/kotlin/B.kt", 'val config = System.getenv("XDG_CONFIG_HOME")'))).toEqual(["env-dirs-kt"])
    expect(ids(kt("apps/desktop/src/main/kotlin/B.kt", 'val run = System.getenv("XDG_RUNTIME_DIR")'))).toEqual(["env-dirs-kt"])
  })

  // env-dirs-kt is about folders: XDG's session variables say which desktop runs, not where a file goes.
  test("an XDG variable that is not a folder is not an env-dirs decision", () => {
    expect(kt("apps/desktop/src/main/kotlin/B.kt", 'val wayland = System.getenv("XDG_SESSION_TYPE") == "wayland"')).toEqual([])
    expect(kt("apps/desktop/src/main/kotlin/B.kt", 'val desktop = System.getenv("XDG_CURRENT_DESKTOP")')).toEqual([])
  })

  // Platform.kt: a shared screen never names the machine's own clipboard or link opener. Compose's handlers
  // differ per app (AWT on the desktop, an Intent on Android that throws when nothing takes the link);
  // Platform's copyToClipboard and openUrl are each app's one implementation, and never throw.
  test("a shared screen copies and opens links through its Platform", () => {
    expect(ids(kt("apps/ui/src/commonMain/kotlin/A.kt", "val clipboard = LocalClipboardManager.current"))).toEqual(["platform-seam-kt"])
    expect(ids(kt("apps/ui/src/commonMain/kotlin/A.kt", "val uri = LocalUriHandler.current"))).toEqual(["platform-seam-kt"])
    expect(ids(kt("apps/android/src/main/kotlin/B.kt", "import androidx.compose.ui.platform.LocalUriHandler"))).toEqual(["platform-seam-kt"])
    // The libraries below :ui keep their own clipboards (TerminalClipboard, EditorClipboard): they have no Platform.
    expect(kt("apps/terminal-compose/src/commonMain/kotlin/dev/supermux/terminal/compose/TerminalClipboard.kt", "val manager = LocalClipboardManager.current")).toEqual([])
    expect(kt("apps/editor-compose/src/commonMain/kotlin/dev/supermux/editor/compose/EditorClipboard.kt", "val manager = LocalClipboardManager.current")).toEqual([])
  })

  test("HostPaths and the desktop's OS seams are allowed", () => {
    expect(kt("apps/shared/src/commonMain/kotlin/dev/supermux/fs/HostPaths.kt", "path.split('/')")).toEqual([])
    expect(kt("apps/desktop/src/main/kotlin/dev/supermux/desktop/host/OsEnv.kt", "ProcessBuilder(argv)")).toEqual([])
  })
})

describe("platform-ok markers", () => {
  test("a reason on the line, or the line above, exempts it", () => {
    expect(ts("src/a.ts", 'const [, id] = route.split("/") // platform-ok: url route')).toEqual([])
    expect(ts("src/a.ts", '// platform-ok: model id\nconst [provider] = model.split("/")')).toEqual([])
  })

  test("a marker without a reason is its own violation", () => {
    expect(ids(ts("src/a.ts", 'const [provider] = model.split("/") // platform-ok:'))).toEqual(["platform-ok"])
  })
})

describe("compare", () => {
  const baseline: Counts = { "os-check": { "src/a.ts": 2, "src/b.ts": 1 } }

  test("a count that rises, or a new file, is drift up", () => {
    const drift = compare({ "os-check": { "src/a.ts": 3, "src/b.ts": 1, "src/c.ts": 1 } }, baseline)
    expect(drift.rose).toEqual([
      { rule: "os-check", file: "src/a.ts", was: 2, now: 3 },
      { rule: "os-check", file: "src/c.ts", was: 0, now: 1 },
    ])
    expect(drift.fell).toEqual([])
  })

  test("a count that falls, or a file that is gone, is drift down", () => {
    const drift = compare({ "os-check": { "src/a.ts": 1 } }, baseline)
    expect(drift.rose).toEqual([])
    expect(drift.fell).toEqual([
      { rule: "os-check", file: "src/a.ts", was: 2, now: 1 },
      { rule: "os-check", file: "src/b.ts", was: 1, now: 0 },
    ])
  })

  test("countHits groups by rule and file", () => {
    const hits = ts("src/a.ts", 'process.platform\nprocess.platform\nimport { posix } from "path"')
    expect(countHits(hits)).toEqual({ "os-check": { "src/a.ts": 2 }, "path-shape": { "src/a.ts": 1 } })
  })
})

describe("sourceFiles", () => {
  test("finds the Kotlin main sources of app modules and editor plugins, not their tests", async () => {
    const { mkdtempSync, mkdirSync, writeFileSync, rmSync } = await import("node:fs")
    const { tmpdir } = await import("node:os")
    const { dirname, join } = await import("node:path")
    const { sourceFiles } = await import("./platform-boundary")
    const root = mkdtempSync(join(tmpdir(), "mux-guard-"))
    try {
      for (const file of [
        "apps/ui/src/commonMain/kotlin/A.kt",
        "apps/ui/src/jvmTest/kotlin/ATest.kt",
        "apps/editor-plugins/lsp/src/jvmMain/kotlin/B.kt",
        "apps/desktop/src/test/kotlin/CTest.kt",
        "src/core/x.ts",
        "src/core/x.test.ts",
        "packages/supermux-core/tests/y.ts",
      ]) {
        mkdirSync(join(root, dirname(file)), { recursive: true })
        writeFileSync(join(root, file), "")
      }
      expect(sourceFiles(root).map(f => f.file)).toEqual([
        "apps/editor-plugins/lsp/src/jvmMain/kotlin/B.kt",
        "apps/ui/src/commonMain/kotlin/A.kt",
        "src/core/x.ts",
      ])
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
