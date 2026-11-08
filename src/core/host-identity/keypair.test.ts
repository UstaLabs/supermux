import { expect, mock, test } from "bun:test"
import * as fs from "fs"
import { mkdtempSync, existsSync, statSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { loadOrCreateHostKey, hostIdFromPublicKey } from "./keypair"

function freshKeyPath(): string {
  return join(mkdtempSync(join(tmpdir(), "mux-hostkey-")), "host-key")
}

test("hostId is 26 lowercase base32 chars, deterministic from the public key", () => {
  const p = freshKeyPath()
  const a = loadOrCreateHostKey(p)
  expect(a.hostId).toMatch(/^[a-z2-7]{26}$/)
  expect(hostIdFromPublicKey(a.publicKeyRaw)).toBe(a.hostId)
})

test("second load returns the SAME identity (persisted, not regenerated)", () => {
  const p = freshKeyPath()
  const a = loadOrCreateHostKey(p)
  const b = loadOrCreateHostKey(p)
  expect(b.hostId).toBe(a.hostId)
  expect(b.publicKeyRaw.equals(a.publicKeyRaw)).toBe(true)
})

test("concurrent first starts all adopt one durable identity", async () => {
  const p = freshKeyPath()
  const modulePath = join(import.meta.dir, "keypair.ts")
  const script = `import { loadOrCreateHostKey } from ${JSON.stringify(modulePath)}; console.log(loadOrCreateHostKey(${JSON.stringify(p)}).hostId)`
  const processes = Array.from({ length: 12 }, () =>
    Bun.spawn([process.execPath, "-e", script], { stdout: "pipe", stderr: "pipe" }))
  const ids = await Promise.all(processes.map(async process => {
    const [stdout, stderr, exitCode] = await Promise.all([
      new Response(process.stdout).text(), new Response(process.stderr).text(), process.exited])
    // A loser that read the winner's key before it was whole died here (an empty or half PEM).
    expect({ exitCode, stderr }).toEqual({ exitCode: 0, stderr: "" })
    return stdout.trim()
  }))

  expect(new Set(ids).size).toBe(1)
  expect(loadOrCreateHostKey(p).hostId).toBe(ids[0]!)
})

test("a first start that runs while another is writing its key adopts that key, never a half-written one", () => {
  const p = freshKeyPath()
  const real = { ...fs }
  // The first exclusive write of a key pauses after creating its file, before writing a byte, and a second first
  // start runs right there: what the concurrent test above can only hit by chance.
  let second: { hostId?: string; error?: unknown } | undefined
  mock.module("fs", () => ({
    ...real,
    writeFileSync(file: fs.PathOrFileDescriptor, data: string | NodeJS.ArrayBufferView, options?: fs.WriteFileOptions) {
      if (second !== undefined || typeof options !== "object" || options?.flag !== "wx") return real.writeFileSync(file, data, options)
      const fd = real.openSync(file as fs.PathLike, "wx", options.mode ?? 0o600)
      second = {}
      try { second.hostId = loadOrCreateHostKey(p).hostId } catch (error) { second.error = error }
      real.writeSync(fd, data as string)
      real.closeSync(fd)
    },
  }))
  const first = loadOrCreateHostKey(p)
  expect(second?.error).toBeUndefined()
  expect(second?.hostId).toBe(first.hostId)
  expect(loadOrCreateHostKey(p).hostId).toBe(first.hostId)
})

test("key file is created 0600", () => {
  const p = freshKeyPath()
  loadOrCreateHostKey(p)
  expect(existsSync(p)).toBe(true)
  expect(statSync(p).mode & 0o777).toBe(0o600)
})

test("sign/verify round-trips; a tampered message fails", () => {
  const p = freshKeyPath()
  const id = loadOrCreateHostKey(p)
  const msg = Buffer.from("challenge-nonce-123")
  const sig = id.sign(msg)
  expect(id.verify(msg, sig)).toBe(true)
  expect(id.verify(Buffer.from("challenge-nonce-124"), sig)).toBe(false)
})
