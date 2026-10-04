import { chmod, lstat, mkdir, open, readdir, readlink, rm, rmdir, symlink, unlink } from "node:fs/promises"
import { homedir } from "node:os"
import { basename, isAbsolute, join, resolve } from "node:path"
import { CoreError } from "../errors.js"
import { assertVaultId } from "./vault.js"
import type { Account, HomeLayout, HomeRoots } from "./types.js"

export function claudeRoot(roots: HomeRoots = {}): string {
  return resolve(roots.claudeRoot ?? process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), ".claude"))
}

export function codexRoot(roots: HomeRoots = {}): string {
  return resolve(roots.codexRoot ?? process.env.CODEX_HOME ?? join(homedir(), ".codex"))
}

/** Claude: history, settings and extensions shared; .credentials.json, .claude.json, sessions/, ide/ stay private. */
export function claudeLayout(roots: HomeRoots = {}): HomeLayout {
  return {
    root: () => claudeRoot(roots),
    shared: [
      { name: "projects", type: "dir" }, { name: "skills", type: "dir" }, { name: "commands", type: "dir" },
      { name: "agents", type: "dir" }, { name: "plugins", type: "dir" },
      { name: "history.jsonl", type: "file" }, { name: "settings.json", type: "file", initial: "{}\n" },
      { name: "CLAUDE.md", type: "file" },
    ],
  }
}

/** Codex: threads shared; auth.json, models_cache.json and the sqlite state stay private. */
export function codexLayout(roots: HomeRoots = {}): HomeLayout {
  return {
    root: () => codexRoot(roots),
    shared: [
      { name: "sessions", type: "dir" }, { name: "archived_sessions", type: "dir" }, { name: "thread-writer-locks", type: "dir" },
      { name: "session_index.jsonl", type: "file" }, { name: "history.jsonl", type: "file" },
    ],
  }
}

export function homePath(homesDirectory: string, account: Pick<Account, "id" | "agent">): string {
  assertVaultId(account.id)
  if (!/^[a-zA-Z0-9_-]{1,128}$/.test(account.agent)) throw new CoreError("invalid_input", "Agent id cannot name an account home")
  return join(homesDirectory, account.agent, account.id)
}

/**
 * Creates `<homesDirectory>/<agent>/<id>/` (0700) and links each shared entry to the layout root
 * (creating the root entry when missing). Idempotent. An existing non-link entry is replaced only
 * when it is empty; otherwise `account_home_conflict`. Isolated accounts (or no layout) get no links.
 * Windows: directories become junctions; shared files are unsupported.
 */
export async function ensureHome(homesDirectory: string, account: Pick<Account, "id" | "agent" | "isolated">, layout?: HomeLayout): Promise<string> {
  const home = homePath(homesDirectory, account)
  await mkdir(home, { recursive: true, mode: 0o700 })
  await chmod(home, 0o700)
  if (account.isolated || !layout) return home
  const root = resolve(layout.root())
  if (!isAbsolute(root) || resolve(home).startsWith(root + "/")) throw new CoreError("invalid_options", "Account history root must be absolute and outside the account home")
  for (const entry of layout.shared) {
    if (!entry.name || basename(entry.name) !== entry.name || entry.name === "." || entry.name === "..") throw new CoreError("invalid_options", `Invalid shared entry ${entry.name}`)
    const target = join(root, entry.name)
    await ensureRootEntry(root, target, entry.type, entry.initial)
    await link(join(home, entry.name), target, entry.type)
  }
  return home
}

async function ensureRootEntry(root: string, target: string, type: "dir" | "file", initial?: string): Promise<void> {
  await mkdir(root, { recursive: true, mode: 0o700 })
  if (type === "dir") { await mkdir(target, { recursive: true, mode: 0o700 }); return }
  try {
    const file = await open(target, "wx", 0o600)
    try { if (initial) await file.writeFile(initial) } finally { await file.close() }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error
  }
}

async function link(path: string, target: string, type: "dir" | "file"): Promise<void> {
  const windows = process.platform === "win32"
  if (windows && type === "file") throw new CoreError("unsupported_operation", `Shared files (${basename(path)}) need symlinks, which supermux does not create on Windows; use an isolated account`)
  let existing
  try { existing = await lstat(path) } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error
  }
  if (existing?.isSymbolicLink()) {
    if (resolve(join(path, ".."), await readlink(path)) === target) return
    await unlink(path)
  } else if (existing?.isDirectory()) {
    if ((await readdir(path)).length) throw conflict(path)
    await rmdir(path)
  } else if (existing) {
    if (!existing.isFile() || existing.size > 0) throw conflict(path)
    await rm(path)
  }
  await symlink(target, path, type === "dir" ? (windows ? "junction" : "dir") : "file")
}

function conflict(path: string): CoreError {
  return new CoreError("account_home_conflict", `${path} exists and is not empty; move it away before sharing history into this account home`)
}
