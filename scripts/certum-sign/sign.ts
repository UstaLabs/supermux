// #116: sign Windows files (PE and MSI) with the Certum SimplySign certificate. signtool handles every file format
// through its digest mode; our cloud client ([cloud.ts]) only ever signs a SHA-256 digest:
//
//   signtool sign /dg <work> /fd sha256 /f <cert> /ac <CA> <file>   → <work>/<file>.dig (the to-be-signed digest, base64)
//   the cloud signs that digest                                      → <work>/<file>.dig.signed (the signature, base64)
//   signtool sign /di <work> <file>                                  → the signature in the file
//   signtool timestamp /tr http://time.certum.pl /td sha256 <file>   → an RFC 3161 countersignature
//
// Run as `bun scripts/certum-sign/sign.ts <file>…` on the Windows release job with SIMPLYSIGN_EMAIL, SIMPLYSIGN_OTP_URI
// and SIGNTOOL in the environment: one login for all the files given, then the files one by one. Only the public
// certificate is written (it is needed by /dg); the e-mail, the seed, the one-time code and the token stay in memory
// and are never printed.
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { basename, join } from "node:path"
import { httpFetch, login, requestSignature, signingCard } from "./cloud"
import { codeAt, parseOtpUri, windowOf } from "./totp"

export const TIMESTAMP_URL = "http://time.certum.pl"

/** Certum's Code Signing 2021 CA, the issuer of our certificate, embedded so a signature carries its whole chain. */
export const CERTUM_CA = join(import.meta.dir, "certum-code-signing-2021-ca.cer")

export function digestArgv(signtool: string, work: string, certificate: string, ca: string, file: string): string[] {
  return [signtool, "sign", "/dg", work, "/fd", "sha256", "/f", certificate, "/ac", ca, "/d", "supermux", "/du", "https://supermux.dev", file]
}

export function ingestArgv(signtool: string, work: string, file: string): string[] {
  return [signtool, "sign", "/di", work, file]
}

export function timestampArgv(signtool: string, file: string): string[] {
  return [signtool, "timestamp", "/tr", TIMESTAMP_URL, "/td", "sha256", file]
}

export interface SignFilesOptions {
  signtool: string
  /** A private scratch folder for the .dig / .p7u / .dig.signed files. */
  work: string
  /** The signing certificate (public) and its issuing CA, as files for signtool. */
  certificate: string
  ca: string
  files: string[]
  /** Runs one signtool command; its exit code. */
  run: (argv: string[]) => Promise<number>
  /** Signs one SHA-256 digest; the raw RSA signature. */
  signDigest: (digest: Buffer) => Promise<Buffer>
}

/** Sign [files] one by one; the first failure stops the run, naming the file and the step. */
export async function signFiles(o: SignFilesOptions): Promise<void> {
  const step = async (argv: string[], what: string, file: string) => {
    const exit = await o.run(argv)
    if (exit !== 0) throw new Error(`signtool ${what} failed for ${file} (exit ${exit})`)
  }
  for (const file of o.files) {
    await step(digestArgv(o.signtool, o.work, o.certificate, o.ca, file), "/dg", file)
    const name = basename(file.replaceAll("\\", "/"))
    const digest = Buffer.from(readFileSync(join(o.work, `${name}.dig`), "utf8").trim(), "base64")
    if (digest.length !== 32) throw new Error(`signtool's digest for ${file} is not SHA-256 (${digest.length} bytes)`)
    const signature = await o.signDigest(digest)
    writeFileSync(join(o.work, `${name}.dig.signed`), signature.toString("base64"))
    await step(ingestArgv(o.signtool, o.work, file), "/di", file)
    await step(timestampArgv(o.signtool, file), "timestamp", file)
  }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** The one-time code for a fresh window: one with at least 5 s left, so it is not spent or expired on the way. */
async function freshCode(uri: string): Promise<string> {
  const otp = parseOtpUri(uri)
  try {
    const left = windowOf(otp, Date.now() / 1000).secondsLeft
    if (left < 5) await sleep(left * 1000 + 250)
    return codeAt(otp, Date.now() / 1000)
  } finally {
    otp.secret.fill(0)
  }
}

async function main(files: string[]): Promise<void> {
  const email = process.env.SIMPLYSIGN_EMAIL ?? ""
  const uri = process.env.SIMPLYSIGN_OTP_URI ?? ""
  const signtool = process.env.SIGNTOOL ?? ""
  if (!email || !uri) throw new Error("SIMPLYSIGN_EMAIL and SIMPLYSIGN_OTP_URI must be set")
  if (!signtool) throw new Error("SIGNTOOL must name signtool.exe")
  if (files.length === 0) throw new Error("no files to sign")

  // One login for every file given: a refused code is never retried here (repeated refusals lock the account).
  const session = await login(httpFetch, email, await freshCode(uri))
  const card = await signingCard(httpFetch, session)
  const work = mkdtempSync(join(tmpdir(), "certum-sign-"))
  try {
    const certificate = join(work, "signing.cer")
    writeFileSync(certificate, card.certificatePem)
    await signFiles({
      signtool, work, certificate, ca: CERTUM_CA, files,
      run: async (argv) => {
        const proc = Bun.spawn(argv, { stdout: "inherit", stderr: "inherit" })
        return await proc.exited
      },
      signDigest: (digest) => requestSignature(httpFetch, session, card, digest),
    })
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
  console.log(`signed ${files.length} file(s)`)
}

if (import.meta.main) {
  main(process.argv.slice(2)).catch((error: unknown) => {
    console.error(`::error::signing failed: ${error instanceof Error ? error.message : String(error)}`)
    process.exit(1)
  })
}
