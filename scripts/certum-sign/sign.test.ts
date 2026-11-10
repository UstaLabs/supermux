// #116: signtool does each file format (PE and MSI) through its digest mode; our client only signs the digest.
//   signtool sign /dg <work> /fd sha256 /f <cert> /ac <CA> <file>   → <work>/<file>.dig (the to-be-signed digest, base64)
//   (the cloud signs it)                                             → <work>/<file>.dig.signed (the signature, base64)
//   signtool sign /di <work> <file>                                  → the signature in the file
//   signtool timestamp /tr http://time.certum.pl /td sha256 <file>   → RFC 3161 countersignature
import { expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { basename, join } from "node:path"
import { digestArgv, ingestArgv, signFiles, timestampArgv, TIMESTAMP_URL } from "./sign"

const ST = "C:\\Kits\\x64\\signtool.exe"

test("the three signtool steps, with the certificate, the CA and Certum's RFC 3161 timestamp", () => {
  expect(digestArgv(ST, "W", "c.cer", "ca.cer", "f.exe")).toEqual([ST, "sign", "/dg", "W", "/fd", "sha256", "/f", "c.cer", "/ac", "ca.cer", "/d", "supermux", "/du", "https://supermux.dev", "f.exe"])
  expect(ingestArgv(ST, "W", "f.exe")).toEqual([ST, "sign", "/di", "W", "f.exe"])
  expect(timestampArgv(ST, "f.exe")).toEqual([ST, "timestamp", "/tr", TIMESTAMP_URL, "/td", "sha256", "f.exe"])
  expect(TIMESTAMP_URL).toBe("http://time.certum.pl")
})

test("each file: the digest signtool wrote is signed by the cloud and handed back, then timestamped", async () => {
  const work = mkdtempSync(join(tmpdir(), "sign-"))
  const ran: string[][] = []
  const digests: string[] = []
  const run = async (argv: string[]) => {
    ran.push(argv)
    // signtool names its files after the file's own name (a Windows path here).
    const name = basename(argv.at(-1)!.replaceAll("\\", "/"))
    if (argv[2] === "/dg") writeFileSync(join(argv[3]!, `${name}.dig`), Buffer.alloc(32, argv.at(-1)!.length).toString("base64"))
    if (argv[2] === "/di") expect(existsSync(join(argv[3]!, `${name}.dig.signed`))).toBe(true)
    return 0
  }
  const signDigest = async (d: Buffer) => { digests.push(d.toString("hex")); return Buffer.alloc(384, 9) }
  await signFiles({ signtool: ST, work, certificate: "c.cer", ca: "ca.cer", files: ["a\\x.exe", "b\\yy.msi"], run, signDigest })
  expect(ran.map((a) => `${a[1]} ${a[2]} ${a.at(-1)}`)).toEqual([
    "sign /dg a\\x.exe", "sign /di a\\x.exe", "timestamp /tr a\\x.exe",
    "sign /dg b\\yy.msi", "sign /di b\\yy.msi", "timestamp /tr b\\yy.msi",
  ])
  expect(digests).toEqual([Buffer.alloc(32, 7).toString("hex"), Buffer.alloc(32, 8).toString("hex")])
  expect(readFileSync(join(work, "x.exe.dig.signed"), "utf8")).toBe(Buffer.alloc(384, 9).toString("base64"))
})

test("a signtool step that fails stops the run and names the file and the step", async () => {
  const work = mkdtempSync(join(tmpdir(), "sign-"))
  const run = async (argv: string[]) => (argv[2] === "/dg" ? 1 : 0)
  await expect(signFiles({ signtool: ST, work, certificate: "c", ca: "ca", files: ["x.exe"], run, signDigest: async () => Buffer.alloc(384) }))
    .rejects.toThrow("signtool /dg failed for x.exe (exit 1)")
})

test("a digest that is not 32 bytes is refused before it reaches the cloud", async () => {
  const work = mkdtempSync(join(tmpdir(), "sign-"))
  let asked = 0
  const run = async (argv: string[]) => {
    if (argv[2] === "/dg") writeFileSync(join(argv[3]!, "x.exe.dig"), Buffer.alloc(20).toString("base64"))
    return 0
  }
  await expect(signFiles({ signtool: ST, work, certificate: "c", ca: "ca", files: ["x.exe"], run, signDigest: async () => { asked++; return Buffer.alloc(384) } }))
    .rejects.toThrow(/SHA-256/)
  expect(asked).toBe(0)
})
