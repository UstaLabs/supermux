import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { downloadAttachment } from "../src/core/session-manager/download"

let dir: string
beforeEach(() => { dir = mkdtempSync(join(tmpdir(), "agentmux-dl-")) })
afterEach(() => rmSync(dir, { recursive: true, force: true }))

function makeFakeApi(payload: { file_path: string; bytes: Buffer; fail?: string }) {
  return {
    token: "FAKETOKEN",
    getFile: async (file_id: string) => {
      if (payload.fail) throw new Error(payload.fail)
      return { file_path: payload.file_path, file_size: payload.bytes.length }
    },
    fetchFile: async (file_path: string) => payload.bytes,
  }
}

test("downloads file and returns local path", async () => {
  const api = makeFakeApi({ file_path: "voice/file_1.oga", bytes: Buffer.from("hello-bytes") })
  const path = await downloadAttachment(api as any, "FILEID123", dir)
  expect(existsSync(path)).toBe(true)
  expect(readFileSync(path)).toEqual(Buffer.from("hello-bytes"))
})

test("preserves extension from telegram file_path", async () => {
  const api = makeFakeApi({ file_path: "voice/file_1.oga", bytes: Buffer.from("x") })
  const path = await downloadAttachment(api as any, "F1", dir)
  expect(path.endsWith(".oga")).toBe(true)
})

test("uses unique filenames to avoid clobber", async () => {
  const api = makeFakeApi({ file_path: "img/foo.jpg", bytes: Buffer.from("a") })
  const p1 = await downloadAttachment(api as any, "F1", dir)
  const p2 = await downloadAttachment(api as any, "F2", dir)
  expect(p1).not.toBe(p2)
})

test("propagates getFile errors as exceptions", async () => {
  const api = makeFakeApi({ file_path: "", bytes: Buffer.alloc(0), fail: "file too big" })
  await expect(downloadAttachment(api as any, "F", dir)).rejects.toThrow(/file too big/)
})

test("rejects path traversal in telegram file_path", async () => {
  const api = makeFakeApi({ file_path: "../../etc/passwd", bytes: Buffer.from("nope") })
  await expect(downloadAttachment(api as any, "F", dir)).rejects.toThrow(/invalid file_path/)
})
