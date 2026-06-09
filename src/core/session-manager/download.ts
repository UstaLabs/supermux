import { mkdirSync, writeFileSync } from "fs"
import { extname, basename, join, normalize, sep } from "path"
import { randomBytes } from "crypto"
import type { FileStore } from "../files/store"

export type DownloadableApi = {
  token?: string
  getFile: (file_id: string) => Promise<{ file_path?: string; file_size?: number }>
  fetchFile?: (file_path: string) => Promise<Buffer>
}

/**
 * Resolve a download_attachment request to a local path on disk.
 *
 * Synthetic broker file_ids (32-hex) resolve through FileStore — no network
 * call. Telegram-issued file_ids fall through to the legacy fetch path that
 * stages bytes into `inboxDir`. If the id looks synthetic but isn't in the
 * store, we still fall through (the network path will produce a sensible
 * "not found" error rather than a silent 404).
 */
export async function resolveDownloadAttachment(opts: {
  file_id: string
  fileStore: FileStore
  telegramApi: DownloadableApi | undefined
  inboxDir: string
}): Promise<{ path: string; via: "filestore" | "telegram" }> {
  const { file_id, fileStore, telegramApi, inboxDir } = opts
  if (/^[0-9a-f]{32}$/.test(file_id)) {
    const meta = await fileStore.get(file_id)
    if (meta) return { path: meta.path, via: "filestore" }
    // fall through — legacy path may still 404, that's the correct outcome.
  }
  if (!telegramApi) throw new Error("download_attachment: Telegram is not configured (no MUX_TELEGRAM_BOT_TOKEN)")
  const path = await downloadAttachment(telegramApi, file_id, inboxDir)
  return { path, via: "telegram" }
}

const TELEGRAM_FILE_BASE = "https://api.telegram.org/file/bot"

export async function downloadAttachment(api: DownloadableApi, file_id: string, inboxDir: string): Promise<string> {
  const meta = await api.getFile(file_id)
  if (!meta.file_path) throw new Error("telegram getFile returned no file_path")

  // Telegram's file_path is server-controlled but should be a relative subpath.
  // Reject traversal attempts — defense in depth.
  const fp = meta.file_path
  if (fp.startsWith("/") || fp.includes("..") || normalize(fp) !== fp.split("/").join(sep)) {
    throw new Error(`invalid file_path from telegram: ${JSON.stringify(fp)}`)
  }

  let bytes: Buffer
  if (api.fetchFile) {
    bytes = await api.fetchFile(fp)
  } else {
    if (!api.token) throw new Error("downloadAttachment: no token and no fetchFile")
    const url = `${TELEGRAM_FILE_BASE}${api.token}/${fp}`
    const res = await fetch(url)
    if (!res.ok) throw new Error(`telegram file fetch failed: ${res.status} ${res.statusText}`)
    bytes = Buffer.from(await res.arrayBuffer())
  }

  mkdirSync(inboxDir, { recursive: true, mode: 0o700 })
  const ext = extname(basename(fp)) || ""
  const unique = `${Date.now()}-${randomBytes(4).toString("hex")}${ext}`
  const outPath = join(inboxDir, unique)
  writeFileSync(outPath, bytes, { mode: 0o600 })
  return outPath
}
