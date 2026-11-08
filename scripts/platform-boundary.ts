// The platform guard (card #29, platform-layer spec §4.1). Feature code must not make OS decisions in place: it
// calls the runtime (node:path, node:os, Bun.spawn, java.nio.file) or the platform layer (@supermux/platform,
// HostPaths, OsEnv). Each rule names a pattern that means "an OS decision made here", where it is allowed, and what
// to call instead.
//
// Today's code has many such sites, so tests/platform-boundary.baseline.json records how many each file has per
// rule, and tests/platform-boundary.test.ts fails when a count RISES (a new OS decision) or FALLS (lower the
// baseline: it only shrinks). Rewrite the baseline from the current tree with:
//   bun scripts/platform-boundary.ts --write
// A line that matches but is no OS decision (a URL, a model id) carries `// platform-ok: <why>` on the same line
// or the line above. The reason is required. Developer and CI tooling (DEV_TOOLING, each folder with its reason) is
// not scanned: it ships to no one.
import { Glob } from "bun"
import { existsSync, readFileSync, writeFileSync } from "node:fs"
import { join, resolve } from "node:path"

export type Lang = "ts" | "kt"
export type Rule = { id: string; lang: Lang; patterns: RegExp[]; allowed: string[]; use: string }
export type Hit = { rule: string; file: string; line: number; text: string }
export type Counts = Record<string, Record<string, number>>
export type Change = { rule: string; file: string; was: number; now: number }
export type Drift = { rose: Change[]; fell: Change[] }

const ROOT = resolve(import.meta.dir, "..")
export const BASELINE = join(ROOT, "tests", "platform-boundary.baseline.json")
const DESKTOP = "apps/desktop/src/main/kotlin/dev/supermux/desktop"
// The webview library ships on its own, without the desktop's OsEnv or folder helper: its native loaders and engine
// runtimes are its OS seams, each allowed under the one rule it needs (never os-check-kt: it asks Skiko's hostOs). Its
// sample and feasibility labs are harnesses that drive the OS on purpose (xdotool, PowerShell, temp folders).
const WEBVIEW = "apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine"
const LSP_TRANSPORT = "apps/editor-plugins/lsp/src/jvmMain/kotlin/dev/supermux/editor/plugins/lsp/ProcessLspTransport.kt"
const WEBVIEW_HARNESSES = ["apps/webview-sample/", "apps/webview-wpetest/", "apps/webview-wintest/"]

/**
 * Developer and CI tooling, with why it is: it is never part of the broker, the CLI or an app a user runs, so no rule
 * applies to it and it is not scanned. A file in one of these folders that ships after all is in [SHIPPED_TOOLING].
 */
export const DEV_TOOLING: Record<string, string> = {
  "scripts/": "the repo's build, CI, release and probe scripts, run by its developers and its CI",
  "packages/supermux-core/scripts/": "the library's live probes against real agents, run by its developers",
  "packages/supermux-core/examples/": "examples a library user reads and adapts (the package publishes only examples/*.mjs)",
}

/** In a [DEV_TOOLING] folder, yet shipped: `supermux pair` and `supermux revoke` run them (src/cli.ts). Still scanned. */
export const SHIPPED_TOOLING: readonly string[] = ["scripts/pair.ts", "scripts/revoke.ts"]

function devTooling(file: string): boolean {
  return !SHIPPED_TOOLING.includes(file) && Object.keys(DEV_TOOLING).some(folder => file.startsWith(folder))
}

export const RULES: Rule[] = [
  {
    id: "os-check",
    lang: "ts",
    patterns: [
      /\bprocess\.platform\b/,
      /\bos\.(?:platform|type)\(\)/,
      /import\s*\{[^}]*\bplatform\b[^}]*\}\s*from\s*["'](?:node:)?os["']/,
    ],
    allowed: ["packages/platform/"],
    use: "take the OS as a parameter, or ask @supermux/platform: only the platform package reads process.platform",
  },
  {
    id: "env-dirs",
    lang: "ts",
    patterns: [
      /\bprocess\.env\.(?:HOME|USERPROFILE|APPDATA|LOCALAPPDATA|TMPDIR|TEMP|TMP|XDG_[A-Z_]+)\b/,
      /\bprocess\.env\[\s*["'](?:HOME|USERPROFILE|APPDATA|LOCALAPPDATA|TMPDIR|TEMP|TMP|XDG_[A-Z_]+)["']\s*\]/,
      /\bhomedir\(\)/,
      /["'`]\/tmp(?:\/|["'`])/,
    ],
    allowed: ["packages/platform/", "src/shared/home.ts"],
    use: "home(): src/shared/home.ts (it applies the OS's home rule through process.env; Bun's os.homedir() ignores env changes); temp: os.tmpdir() or fs.mkdtemp; app folders: dirs.app()",
  },
  {
    id: "spawn",
    lang: "ts",
    patterns: [
      /\bBun\.spawn(?:Sync)?\(/,
      /\bBun\.\$`/,
      // Not a type-only import or export: a type starts no process.
      /^(?!\s*(?:import|export)\s+type\b).*from\s*["'](?:node:)?child_process["']/,
      /require\(\s*["'](?:node:)?child_process["']\s*\)/,
    ],
    // src/core/git/exec.ts is the git family's async runner, every call there starting from proc.spawnDefaults. Its
    // synchronous twin, exec-sync.ts, goes through the launcher.
    allowed: ["packages/platform/", "src/core/process/launcher.ts", "src/core/git/exec.ts"],
    use: "start processes through src/core/process/launcher.ts: spawnProcess / spawnProcessSync (Bun's options) or spawnCommand / spawnCommandSync (node's, with Windows .cmd/.ps1 shims). Git goes through src/core/git/exec.ts (or exec-sync.ts, where a caller cannot await). Both apply proc.spawnDefaults; without them the child gets the startup env and, on Windows, a console window",
  },
  {
    id: "which-path",
    lang: "ts",
    patterns: [/\bBun\.which\(\s*[^,()]+\)/],
    allowed: [],
    use: "Bun.which(name, { PATH: process.env.PATH }): without PATH it searches the PATH the process started with (oven-sh/bun#40846); an agent CLI to launch: proc.resolve",
  },
  {
    id: "links-perms",
    lang: "ts",
    patterns: [/\bsymlink(?:Sync)?\((?![^\n]*["']junction["'])/, /\b[fl]?chmod(?:Sync)?\(/, /\bmode:\s*0o[0-7]+/],
    allowed: ["packages/platform/", "packages/supermux-core/src/private-files.ts"],
    use: "a folder link: fs.symlink(target, path, \"junction\"); a file link: files.linkFile; a private file: files.makePrivate",
  },
  {
    id: "signals",
    lang: "ts",
    patterns: [/\bprocess\.kill\(\s*-/, /["']SIG(?:KILL|HUP|WINCH|USR1|USR2|QUIT)["']/],
    allowed: ["packages/platform/"],
    use: "stop processes with proc.stop / proc.killTree: Windows has no signals",
  },
  {
    id: "path-shape",
    lang: "ts",
    patterns: [
      /import\s*\{[^}]*\b(?:posix|win32)\b[^}]*\}\s*from\s*["'](?:node:)?path["']/,
      /\bpath\.(?:posix|win32)\b/,
      /\.startsWith\([^)]*\+\s*["'`]\/["'`]\s*\)/,
      /\.startsWith\([^)]*\+\s*(?:\w+\.)?sep\s*\)/,
      /\.split\(\s*["'`]\/["'`]\s*\)/,
      /\.(?:startsWith|endsWith|lastIndexOf|indexOf|includes)\(\s*["'`]\/["'`]\s*\)/,
    ],
    allowed: ["packages/platform/", "src/channels/web/index.ts"],
    use: "filesystem paths: node:path (relative, basename, dirname) or hostPaths.isInside; a URL, route or id: mark the line // platform-ok: url",
  },
  {
    id: "os-check-kt",
    lang: "kt",
    patterns: [/System\.getProperty\(\s*"os\.name"\s*\)/, /File\.separatorChar\s*==/, /\bnavigator\.platform\b/, /\buserAgent\b/],
    allowed: [
      `${DESKTOP}/platform/DesktopOs.kt`,
      "apps/editor-compose/src/jvmMain/kotlin/dev/supermux/editor/compose/Platform.jvm.kt",
      "apps/editor-compose/src/wasmJsMain/kotlin/dev/supermux/editor/compose/Platform.wasmJs.kt",
      "apps/terminal-core/src/jvmMain/kotlin/dev/supermux/terminal/JvmNativeLibrary.kt",
      "apps/editor-syntax/src/jvmAndAndroidMain/kotlin/dev/supermux/editor/syntax/SesNativeLoader.kt",
    ],
    use: "the desktop asks DesktopOs; shared code and libraries ask Skiko's hostOs (hostPlatform)",
  },
  {
    id: "host-path-kt",
    lang: "kt",
    patterns: [
      /\.split\(\s*(?:'\/'|"\/")\s*\)/,
      /\.substring(?:After|Before)Last\(\s*(?:'\/'|"\/")\s*\)/,
      /\.(?:startsWith|endsWith)\(\s*(?:'\/'|"\/")\s*\)/,
      /\.lastIndexOf\(\s*(?:'\/'|"\/")\s*\)/,
      /\.trimEnd\(\s*'\/'\s*\)/,
      /\.remove(?:Suffix|Prefix)\(\s*"\/"\s*\)/,
    ],
    allowed: ["apps/shared/src/commonMain/kotlin/dev/supermux/fs/HostPaths.kt"],
    use: "a broker's paths go through dev.supermux.fs.HostPaths (a Windows host's C:\\ paths too); a URL, route or id: // platform-ok: url",
  },
  {
    id: "spawn-kt",
    lang: "kt",
    patterns: [/\bProcessBuilder\(/, /Runtime\.getRuntime\(\)\.exec\(/],
    allowed: [
      `${DESKTOP}/host/OsEnv.kt`,
      `${DESKTOP}/platform/DesktopOs.kt`,
      `${WEBVIEW}/linux/WpeRuntime.kt`,
      `${WEBVIEW}/linux/WpeExtractor.kt`,
      ...WEBVIEW_HARNESSES,
      // The editor library's own LSP transport, for its sample and tests: the product's language servers run on the broker.
      LSP_TRANSPORT,
    ],
    use: "run OS commands through OsEnv",
  },
  {
    id: "platform-seam-kt",
    lang: "kt",
    // Compose's own clipboard and link opener: AWT's on the desktop, an Intent on Android that throws when nothing
    // takes the link. The libraries below :ui have no Platform: they use Compose's, which an app can provide.
    patterns: [/\bLocalClipboardManager\b/, /\bLocalUriHandler\b/],
    allowed: ["apps/terminal-compose/", "apps/editor-compose/", "apps/webview-compose/"],
    use: "copy and open links through LocalPlatform.current (copyToClipboard, openUrl): one implementation per app, and neither throws",
  },
  {
    id: "env-dirs-kt",
    lang: "kt",
    patterns: [
      /System\.getProperty\(\s*"(?:user\.home|java\.io\.tmpdir)"\s*\)/,
      // XDG's folder variables (_HOME, _DIRS, XDG_RUNTIME_DIR), not its session ones (XDG_SESSION_TYPE, XDG_CURRENT_DESKTOP).
      /System\.getenv\(\s*"(?:HOME|USERPROFILE|APPDATA|LOCALAPPDATA|XDG_[A-Z_]*(?:_HOME|_DIRS?))"\s*\)/,
      /\bFile\.createTempFile\(/,
    ],
    allowed: [
      // The desktop's one reader of home, the app-data folder and XDG_RUNTIME_DIR.
      `${DESKTOP}/host/OsEnv.kt`,
      // The native-library cache root's call sites; the shared block itself takes them as parameters.
      "apps/terminal-core/src/jvmMain/kotlin/dev/supermux/terminal/JvmNativeLibrary.kt",
      "apps/editor-syntax/src/jvmAndAndroidMain/kotlin/dev/supermux/editor/syntax/SesNativeLoader.kt",
      LSP_TRANSPORT,
      `${WEBVIEW}/windows/WinNativeLoader.kt`,
      `${WEBVIEW}/windows/Wv2Runtime.kt`,
      `${WEBVIEW}/mac/MacNativeLibrary.kt`,
      `${WEBVIEW}/linux/WpeRuntime.kt`,
      ...WEBVIEW_HARNESSES,
    ],
    use: "folders: the desktop dirs helper (platform step 9); temp files: Files.createTempFile / createTempDirectory",
  },
]

const COMMENT_LINE = /^\s*(?:\/\/|\*|\/\*)/
const MARKER = /\/\/\s*platform-ok:(.*)$/

function allowedIn(rule: Rule, file: string): boolean {
  return rule.allowed.some(entry => (entry.endsWith("/") ? file.startsWith(entry) : file === entry))
}

/** "ok" when the line carries a marker with a reason, "empty" for a marker without one, "none" otherwise. */
function marker(line: string | undefined): "ok" | "empty" | "none" {
  const match = line?.match(MARKER)
  if (!match) return "none"
  return match[1]!.trim() ? "ok" : "empty"
}

/** One file's hits: lines matching a rule, outside its allowed places, not marked platform-ok. */
export function scanText(file: string, text: string, rules: readonly Rule[]): Hit[] {
  const lines = text.split(/\r?\n/)
  const hits: Hit[] = []
  for (const [index, line] of lines.entries()) {
    if (COMMENT_LINE.test(line)) continue
    const matched = rules.filter(rule => !allowedIn(rule, file) && rule.patterns.some(pattern => pattern.test(line)))
    if (!matched.length) continue
    const here = marker(line)
    const above = COMMENT_LINE.test(lines[index - 1] ?? "") ? marker(lines[index - 1]) : "none"
    if (here === "ok" || above === "ok") continue
    if (here === "empty" || above === "empty") {
      hits.push({ rule: "platform-ok", file, line: index + 1, text: line.trim() })
      continue
    }
    for (const rule of matched) hits.push({ rule: rule.id, file, line: index + 1, text: line.trim() })
  }
  return hits
}

function norm(path: string): string {
  return path.replace(/\\/g, "/")
}

/** The scanned sources, as repository-relative paths with their language. */
export function sourceFiles(root: string = ROOT): { file: string; lang: Lang }[] {
  const files: { file: string; lang: Lang }[] = []
  for (const dir of ["src", "packages", "scripts"]) {
    if (!existsSync(join(root, dir))) continue
    for (const path of new Glob("**/*.ts").scanSync({ cwd: join(root, dir) })) {
      const file = norm(`${dir}/${path}`)
      const segments = file.split("/")
      if (segments.some(s => s === "node_modules" || s === "dist" || s === "tests" || s === "e2e")) continue
      if (/\.(?:test|spec)\.ts$|\.d\.ts$/.test(file) || devTooling(file)) continue
      files.push({ file, lang: "ts" })
    }
  }
  const apps = join(root, "apps")
  if (existsSync(apps)) {
    // Two patterns, not "{*,editor-plugins/*}/src": Bun's Glob matches nothing when a brace alternative holds a "/".
    const modules = ["*/src", "editor-plugins/*/src"].flatMap(pattern => [...new Glob(pattern).scanSync({ cwd: apps, onlyFiles: false })])
    for (const src of modules) {
      for (const path of new Glob("*/**/*.kt").scanSync({ cwd: join(apps, src) })) {
        const set = norm(path).split("/")[0]!
        if (/test$/i.test(set)) continue
        files.push({ file: norm(`apps/${src}/${path}`), lang: "kt" })
      }
    }
  }
  return files.sort((a, b) => a.file.localeCompare(b.file))
}

export function scanRepo(root: string = ROOT): Hit[] {
  return sourceFiles(root).flatMap(({ file, lang }) =>
    scanText(file, readFileSync(join(root, file), "utf8"), RULES.filter(rule => rule.lang === lang)),
  )
}

export function countHits(hits: readonly Hit[]): Counts {
  const counts: Counts = {}
  for (const hit of hits) {
    if (hit.rule === "platform-ok") continue
    const byFile = (counts[hit.rule] ??= {})
    byFile[hit.file] = (byFile[hit.file] ?? 0) + 1
  }
  return counts
}

export function compare(current: Counts, baseline: Counts): Drift {
  const drift: Drift = { rose: [], fell: [] }
  for (const rule of new Set([...Object.keys(current), ...Object.keys(baseline)])) {
    const now = current[rule] ?? {}
    const was = baseline[rule] ?? {}
    for (const file of [...new Set([...Object.keys(now), ...Object.keys(was)])].sort()) {
      const change = { rule, file, was: was[file] ?? 0, now: now[file] ?? 0 }
      if (change.now > change.was) drift.rose.push(change)
      else if (change.now < change.was) drift.fell.push(change)
    }
  }
  return drift
}

/** The baseline file's text: rules and files sorted, so a diff shows exactly what moved. */
export function formatBaseline(counts: Counts): string {
  const sorted: Counts = {}
  for (const rule of Object.keys(counts).sort()) {
    sorted[rule] = {}
    for (const file of Object.keys(counts[rule]!).sort()) sorted[rule]![file] = counts[rule]![file]!
  }
  return `${JSON.stringify(sorted, null, 2)}\n`
}

export function readBaseline(path: string = BASELINE): Counts {
  return existsSync(path) ? (JSON.parse(readFileSync(path, "utf8")) as Counts) : {}
}

if (import.meta.main) {
  if (process.argv[2] !== "--write") {
    console.error("usage: bun scripts/platform-boundary.ts --write   (rewrites tests/platform-boundary.baseline.json)")
    process.exit(2)
  }
  writeFileSync(BASELINE, formatBaseline(countHits(scanRepo())))
  console.log(`wrote ${BASELINE}`)
}
