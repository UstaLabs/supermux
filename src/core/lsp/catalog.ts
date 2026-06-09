import { existsSync } from "node:fs"
import { dirname, join } from "node:path"
import { home } from "../../shared/home"
import { bunGlobalBinDir, muxLspBinDir, muxLspHome } from "./paths"

// Catalog of recommended language servers, keyed by language/extension.
//
// This is pure DATA — nothing in the LSP bridge is hard-coded to a specific
// language. To support a new language you add an entry here. The `install.cmd`
// is the ONLY command the broker will ever run for a server — the web client
// sends a serverId, never a raw command.
//
// Installs run as the broker user (no sudo, no TTY). Node servers use
// `bun install -g`; native tools install under ~/.mux/lsp when needed.

export type LspRuntime = "node" | "native"

export interface LspInstallSpec {
  /** argv the broker runs to install the server. Fixed — never client-supplied. */
  cmd: string[]
  /** human-readable form of the command, shown in the UI. */
  label: string
  /** prerequisite binary that must exist on PATH for the install to work. */
  requires: string
  /** Extra env for the install child (merged with non-interactive defaults). */
  env?: Record<string, string>
}

export interface LspServerSpec {
  id: string
  /** display name shown in the editor's "install this?" prompt. */
  label: string
  runtime: LspRuntime
  /** node: the global-bin name installed under bun; native: the PATH command. */
  bin: string
  /** args passed after the binary (most servers want stdio mode). */
  args: string[]
  /** file extensions (with leading dot) this server handles. */
  extensions: string[]
  /** how to install it, when it's auto-installable. */
  install?: LspInstallSpec
  /** User-defined server: spawn this executable directly. */
  command?: string
  /** LSP languageId when not in the built-in map. */
  languageId?: string
  /** User-added server (shown in settings, removable). */
  custom?: boolean
}

const INSTALL_DART_SDK = join(import.meta.dir, "install-dart-sdk.ts")

const BUN = (pkgs: string[]): LspInstallSpec => ({
  cmd: ["bun", "install", "-g", ...pkgs],
  label: `bun install -g ${pkgs.join(" ")}`,
  requires: "bun",
})

const SERVERS: LspServerSpec[] = [
  {
    id: "typescript",
    label: "TypeScript / JavaScript",
    runtime: "node",
    bin: "typescript-language-server",
    args: ["--stdio"],
    extensions: [".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs"],
    install: BUN(["typescript-language-server", "typescript"]),
  },
  {
    id: "pyright",
    label: "Python (Pyright)",
    runtime: "node",
    bin: "pyright-langserver",
    args: ["--stdio"],
    extensions: [".py", ".pyi"],
    install: BUN(["pyright"]),
  },
  {
    id: "bash",
    label: "Bash",
    runtime: "node",
    bin: "bash-language-server",
    args: ["start"],
    extensions: [".sh", ".bash"],
    install: BUN(["bash-language-server"]),
  },
  {
    id: "yaml",
    label: "YAML",
    runtime: "node",
    bin: "yaml-language-server",
    args: ["--stdio"],
    extensions: [".yaml", ".yml"],
    install: BUN(["yaml-language-server"]),
  },
  {
    id: "json",
    label: "JSON",
    runtime: "node",
    bin: "vscode-json-language-server",
    args: ["--stdio"],
    extensions: [".json", ".jsonc"],
    install: BUN(["vscode-langservers-extracted"]),
  },
  {
    id: "css",
    label: "CSS",
    runtime: "node",
    bin: "vscode-css-language-server",
    args: ["--stdio"],
    extensions: [".css", ".scss", ".less"],
    install: BUN(["vscode-langservers-extracted"]),
  },
  {
    id: "html",
    label: "HTML",
    runtime: "node",
    bin: "vscode-html-language-server",
    args: ["--stdio"],
    extensions: [".html", ".htm"],
    install: BUN(["vscode-langservers-extracted"]),
  },
  {
    id: "gopls",
    label: "Go (gopls)",
    runtime: "native",
    bin: "gopls",
    args: [],
    extensions: [".go"],
    install: {
      cmd: ["go", "install", "golang.org/x/tools/gopls@latest"],
      label: "go install gopls → ~/.mux/lsp/bin (no sudo)",
      requires: "go",
    },
  },
  {
    id: "rust-analyzer",
    label: "Rust (rust-analyzer)",
    runtime: "native",
    bin: "rust-analyzer",
    args: [],
    extensions: [".rs"],
    install: {
      cmd: ["rustup", "component", "add", "rust-analyzer", "--toolchain", "stable"],
      label: "rustup component add rust-analyzer (stable)",
      requires: "rustup",
    },
  },
  {
    id: "dart",
    label: "Dart",
    runtime: "native",
    bin: "dart",
    args: [
      "language-server",
      "--protocol=lsp",
      "--client-id=supermux.web",
      "--client-version=1",
    ],
    extensions: [".dart"],
    install: {
      cmd: ["bun", INSTALL_DART_SDK],
      label: "Download Dart SDK to ~/.mux/lsp (no sudo)",
      requires: "unzip",
    },
  },
]

// LSP `languageId` per extension (the value sent in textDocument/didOpen).
const LANGUAGE_ID: Record<string, string> = {
  ".ts": "typescript", ".mts": "typescript", ".cts": "typescript", ".tsx": "typescriptreact",
  ".js": "javascript", ".mjs": "javascript", ".cjs": "javascript", ".jsx": "javascriptreact",
  ".py": "python", ".pyi": "python", ".go": "go", ".rs": "rust", ".dart": "dart",
  ".sh": "shellscript", ".bash": "shellscript", ".yaml": "yaml", ".yml": "yaml",
  ".json": "json", ".jsonc": "jsonc", ".css": "css", ".scss": "scss", ".less": "less",
  ".html": "html", ".htm": "html",
}

export function extOf(path: string): string {
  const base = path.split("/").pop() ?? path
  const dot = base.lastIndexOf(".")
  return dot <= 0 ? "" : base.slice(dot).toLowerCase()
}

export { bunGlobalBinDir }

/** `dart` on PATH, ~/.mux/lsp/dart-sdk, or a Flutter-bundled SDK. */
export function findDartBin(): string | null {
  const muxDart = join(muxLspHome(), "dart-sdk", "bin", "dart")
  if (existsSync(muxDart)) return muxDart
  try {
    const onPath = Bun.which("dart")
    if (onPath) return onPath
  } catch { /* not on PATH */ }
  let flutter: string | null = null
  try { flutter = Bun.which("flutter") } catch { /* */ }
  if (flutter) {
    const install = dirname(dirname(flutter))
    const sdkDart = join(install, "bin", "cache", "dart-sdk", "bin", "dart")
    if (existsSync(sdkDart)) return sdkDart
  }
  for (const root of [process.env.FLUTTER_ROOT, join(home(), "flutter")]) {
    if (!root) continue
    const sdkDart = join(root, "bin", "cache", "dart-sdk", "bin", "dart")
    if (existsSync(sdkDart)) return sdkDart
  }
  return null
}

export function findGoplsBin(): string | null {
  const mux = join(muxLspBinDir(), "gopls")
  if (existsSync(mux)) return mux
  try {
    return Bun.which("gopls")
  } catch {
    return null
  }
}

export function findRustAnalyzerBin(): string | null {
  const cargo = join(home(), ".cargo", "bin", "rust-analyzer")
  if (existsSync(cargo)) return cargo
  try {
    return Bun.which("rust-analyzer")
  } catch {
    return null
  }
}

// The absolute path (node) or PATH name (native) of a server's executable.
export function resolveBinPath(spec: LspServerSpec): string {
  if (spec.runtime === "node") return join(bunGlobalBinDir(), spec.bin)
  if (spec.id === "dart") return findDartBin() ?? spec.bin
  if (spec.id === "gopls") return findGoplsBin() ?? spec.bin
  if (spec.id === "rust-analyzer") return findRustAnalyzerBin() ?? spec.bin
  return spec.bin
}

// The spawn command for a server. Node servers run through bun since the host
// has no standalone `node`.
export function launchCommand(spec: LspServerSpec): { command: string; args: string[] } {
  if (spec.command) return { command: spec.command, args: spec.args }
  if (spec.runtime === "node") {
    return { command: "bun", args: [resolveBinPath(spec), ...spec.args] }
  }
  if (spec.id === "dart") {
    const dart = findDartBin()
    if (dart) return { command: dart, args: spec.args }
  }
  if (spec.id === "gopls") {
    const gopls = findGoplsBin()
    if (gopls) return { command: gopls, args: spec.args }
  }
  if (spec.id === "rust-analyzer") {
    const ra = findRustAnalyzerBin()
    if (ra) return { command: ra, args: spec.args }
  }
  return { command: spec.bin, args: spec.args }
}

export function getCatalogServerById(id: string): LspServerSpec | undefined {
  return SERVERS.find((s) => s.id === id)
}

/** @deprecated Use registry.languageIdForPath — kept for tests importing catalog only. */
export function languageIdForPath(path: string): string {
  return LANGUAGE_ID[extOf(path)] ?? "plaintext"
}

export { SERVERS }
