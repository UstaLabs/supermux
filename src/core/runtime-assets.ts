// Files that ship INSIDE the compiled binary but whose PATHS are consumed by
// OTHER processes (exec'd helpers, prompt files handed to spawned agents).
// Child processes cannot read /$bunfs/ paths, so these are copied out to
//   <stateDir>/runtime-assets/<BUILD_VERSION>/<name>
// once per boot. Version-keyed: a binary update materializes fresh copies and
// never serves a previous version's files. In source mode callers get the
// real repo paths directly (no copy) — the exported helpers branch on
// IS_COMPILED for exactly that.
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "fs"
import { join, dirname, resolve as resolvePath } from "path"
import { BUILD_VERSION, IS_COMPILED } from "../shared/build-info"

// Embedded sources ($bunfs paths when compiled, repo files in source mode)
import ptyHelperEmbedded from "./terminal/pty-helper" with { type: "file" }
import curatorPromptEmbedded from "../../prompts/knowledge-curator.md" with { type: "file" }
import environmentMdEmbedded from "../../prompts/environment.md" with { type: "file" }
import replyFallbackEmbedded from "../../prompts/reply-fallback.md" with { type: "file" }

export function materializeAsset(opts: { stateDir: string; name: string; sourcePath: string; executable?: boolean }): string {
  const dest = join(opts.stateDir, "runtime-assets", BUILD_VERSION, opts.name)
  if (existsSync(dest)) return dest
  mkdirSync(dirname(dest), { recursive: true, mode: 0o700 })
  // Atomic: write to a tmp in the same dir, chmod it, then rename onto dest —
  // a mid-write crash/ENOSPC can never leave a partial file that the
  // existsSync fast-path above would then trust forever (the file gets
  // exec'd / handed to spawned agents, so a truncated copy is poison).
  //
  // readFileSync+writeFileSync, NOT copyFileSync: in a compiled binary the
  // source is a /$bunfs/ virtual path, and Bun's copyFileSync can't read those
  // (ENOENT) — only the JS fs read shim sees them. read-then-write works for
  // both $bunfs sources (compiled) and real paths (source mode).
  const tmp = `${dest}.tmp.${process.pid}`
  try {
    writeFileSync(tmp, readFileSync(opts.sourcePath))
    if (opts.executable) chmodSync(tmp, 0o755)
    renameSync(tmp, dest)
  } catch (err) {
    try { unlinkSync(tmp) } catch {}
    throw err
  }
  return dest
}

// --- Concrete assets -------------------------------------------------------
//
// Source-mode paths point at the real repo files. runtime-assets.ts lives at
// src/core/, so terminal/pty-helper is one level down and prompts/ is two up.

const PTY_HELPER_SOURCE_PATH = resolvePath(import.meta.dirname, "terminal", "pty-helper")
const REPO_PROMPTS_DIR = resolvePath(import.meta.dirname, "..", "..", "prompts")
const CURATOR_PROMPT_SOURCE_PATH = resolvePath(REPO_PROMPTS_DIR, "knowledge-curator.md")
const ENVIRONMENT_MD_SOURCE_PATH = resolvePath(REPO_PROMPTS_DIR, "environment.md")
const REPLY_FALLBACK_SOURCE_PATH = resolvePath(REPO_PROMPTS_DIR, "reply-fallback.md")

// pty-helper: a committed native ELF that the terminal manager EXEC's. The
// child can't read $bunfs, so it must be a real on-disk file.
export function ptyHelperPath(stateDir: string): string {
  if (!IS_COMPILED) return PTY_HELPER_SOURCE_PATH
  return materializeAsset({ stateDir, name: "pty-helper", sourcePath: ptyHelperEmbedded, executable: true })
}

// knowledge-curator.md: the curator hands this path to a spawned claude session.
export function curatorPromptPath(stateDir: string): string {
  if (!IS_COMPILED) return CURATOR_PROMPT_SOURCE_PATH
  return materializeAsset({ stateDir, name: "knowledge-curator.md", sourcePath: curatorPromptEmbedded })
}

// environment.md: spawn-command.ts passes this path to spawned claude via
// `--append-system-prompt-file`. (Its CONTENT is also read in-process by the
// codex/cursor/opencode preamble-writers via readEnvironmentMd(); that text
// read needs no copy and is unrelated to this path helper.)
export function environmentMdPath(stateDir: string): string {
  if (!IS_COMPILED) return ENVIRONMENT_MD_SOURCE_PATH
  return materializeAsset({ stateDir, name: "environment.md", sourcePath: environmentMdEmbedded })
}

// In-process CONTENT read of environment.md. Lives here, not in
// environment.ts, because of the single-importer rule: bun dedupes modules
// by specifier and IGNORES import attributes, so the same file imported
// `with {type:"file"}` here and `with {type:"text"}` elsewhere silently
// collapses to whichever resolves first (compiled: the text import won and
// environmentMdPath() tried to copyFileSync the document body as a filename
// → ENAMETOOLONG → every Claude spawn failed). One importer, one attribute;
// content readers go through the path — readFileSync of a $bunfs path works
// in-process in compiled mode.
export function environmentMdContent(): string {
  return readFileSync(environmentMdEmbedded, "utf8")
}

// reply-fallback.md: spawn-command.ts passes this path to spawned claude via
// `--append-system-prompt-file` when the mux-core plugin is absent.
export function replyFallbackPath(stateDir: string): string {
  if (!IS_COMPILED) return REPLY_FALLBACK_SOURCE_PATH
  return materializeAsset({ stateDir, name: "reply-fallback.md", sourcePath: replyFallbackEmbedded })
}

// promptsDir: spawn-command.ts grants spawned claude read access to the prompts
// directory via `--add-dir`. In source mode that's the repo prompts/. When
// compiled there is no repo dir on disk, so we materialize environment.md and
// return its containing version-keyed dir. reply-fallback.md is materialized
// separately and conditionally by replyFallbackPath (only when the mux-core
// plugin is absent). The spawned command references each prompt file by its
// absolute path, so the dir listing is informational, not load-bearing.
export function promptsDir(stateDir: string): string {
  if (!IS_COMPILED) return REPO_PROMPTS_DIR
  // Ensure the prompt files exist on disk, then return their containing dir.
  return dirname(environmentMdPath(stateDir))
}
