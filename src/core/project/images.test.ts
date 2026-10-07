import { test, expect, afterEach } from "bun:test"
import { mkdtempSync, rmSync, existsSync, readFileSync, statSync, readdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ProjectImages } from "./images"

const dirs: string[] = []
function tempRoot() {
  const d = mkdtempSync(join(tmpdir(), "project-images-"))
  dirs.push(d)
  return d
}
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

test("isSupported accepts png, jpeg, webp, gif only", () => {
  const imgs = new ProjectImages(join(tempRoot(), "project-images"))
  for (const m of ["image/png", "image/jpeg", "image/webp", "image/gif"]) expect(imgs.isSupported(m)).toBe(true)
  expect(imgs.isSupported("image/svg+xml")).toBe(false)
  expect(imgs.isSupported("text/html")).toBe(false)
})

test("write creates the dir 0700 and stores bytes under a uuid.ext id, leaving no tmp file", () => {
  const dir = join(tempRoot(), "project-images")
  const imgs = new ProjectImages(dir)
  const id = imgs.write(new Uint8Array([1, 2, 3]), "image/jpeg")
  expect(id).toMatch(/^[0-9a-f-]{36}\.jpg$/)
  expect(statSync(dir).mode & 0o777).toBe(0o700)
  expect([...readFileSync(imgs.path(id)!)]).toEqual([1, 2, 3])
  expect(readdirSync(dir)).toEqual([id])
  expect(imgs.mimeOf(id)).toBe("image/jpeg")
})

test("write rejects an unsupported mime", () => {
  const imgs = new ProjectImages(join(tempRoot(), "project-images"))
  expect(() => imgs.write(new Uint8Array([1]), "image/svg+xml")).toThrow()
})

test("path rejects ids that are not uuid.ext (no traversal)", () => {
  const imgs = new ProjectImages(join(tempRoot(), "project-images"))
  expect(imgs.path("../../etc/passwd")).toBeUndefined()
  expect(imgs.path("0f0e0d0c-0b0a-0908-0706-050403020100.exe")).toBeUndefined()
  expect(imgs.path("0f0e0d0c-0b0a-0908-0706-050403020100.png")).toBeDefined()
})

test("remove deletes the file and is best effort for a missing or invalid id", () => {
  const imgs = new ProjectImages(join(tempRoot(), "project-images"))
  const id = imgs.write(new Uint8Array([9]), "image/png")
  imgs.remove(id)
  expect(existsSync(imgs.path(id)!)).toBe(false)
  expect(() => imgs.remove(id)).not.toThrow()
  expect(() => imgs.remove("../x")).not.toThrow()
})
