import { describe, expect, test } from "bun:test"
import { execFileSync } from "child_process"
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { assertContained, findEscape, isInUse } from "./archive-guard"

function tmp(): string {
  return mkdtempSync(join(tmpdir(), "archive-guard-"))
}

describe("findEscape", () => {
  test("a normal tree passes (nested dirs, links that stay inside)", () => {
    const d = tmp()
    mkdirSync(join(d, "a", "b"), { recursive: true })
    writeFileSync(join(d, "a", "b", "opencode"), "x")
    symlinkSync("b/opencode", join(d, "a", "link"))
    expect(findEscape(d)).toBeNull()
  })

  test("a symlink pointing outside is caught (absolute or ../)", () => {
    const d = tmp()
    symlinkSync("/etc/passwd", join(d, "evil"))
    expect(findEscape(d)).toContain("evil -> /etc/passwd")
    const e = tmp()
    mkdirSync(join(e, "x"))
    symlinkSync("../../outside", join(e, "x", "up"))
    expect(() => assertContained(e)).toThrow("reaches outside its folder")
  })

  test("a crafted tar with ../ and an escaping symlink: nothing passes the guard", () => {
    const work = tmp()
    const staging = join(work, "staging")
    mkdirSync(staging)
    // python's tarfile writes the members as given: a zip-slip path and a link out.
    const tgz = join(work, "evil.tar.gz")
    execFileSync("python3", ["-c", `
import tarfile, io
t = tarfile.open(${JSON.stringify(tgz)}, "w:gz")
def add(name, data=b"x"):
    i = tarfile.TarInfo(name); i.size = len(data); t.addfile(i, io.BytesIO(data))
add("ok/opencode")
add("../escaped-by-dotdot")
l = tarfile.TarInfo("ok/link-out"); l.type = tarfile.SYMTYPE; l.linkname = "/etc"; t.addfile(l)
t.close()
`])
    try { execFileSync("tar", ["-xzf", tgz, "-C", staging], { stdio: "ignore" }) } catch {}
    // GNU tar itself drops the ../ member; the guard must catch the link that tar did create.
    expect(existsSync(join(work, "escaped-by-dotdot"))).toBe(false)
    expect(findEscape(staging)).toContain("link-out -> /etc")
  })

  test("entry names that resolve outside (a fake fs listing ../) are caught", () => {
    const fs = {
      list: (dir: string) => (dir === "/s" ? ["..", "fine"] : []),
      isDir: () => false,
      readlink: () => null,
    }
    expect(findEscape("/s", fs)).toBe("/")
  })
})

test("isInUse: the codes Windows gives for a file held open", () => {
  for (const code of ["EPERM", "EBUSY", "EACCES"]) expect(isInUse(Object.assign(new Error(), { code }))).toBe(true)
  expect(isInUse(Object.assign(new Error(), { code: "ENOENT" }))).toBe(false)
})
