// src/core/git/clone.ts
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync, chmodSync, rmSync, existsSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

export function projectDir(root: string, host: string, owner: string, name: string): string {
  return join(root, host, owner, name)
}

export interface CloneOpts {
  url: string                       // https://host/owner/repo.git OR git@host:owner/repo.git OR file://…
  targetDir: string
  https?: { user: string; token: string }  // inject via GIT_ASKPASS (token never in config)
  sshCommand?: string               // value for core.sshCommand / GIT_SSH_COMMAND
}

/** Clone `url` to `targetDir`. Idempotent: if targetDir already a git repo, reuse it. */
export async function gitClone(opts: CloneOpts): Promise<{ localPath: string; reused: boolean }> {
  if (existsSync(join(opts.targetDir, ".git"))) return { localPath: opts.targetDir, reused: true }

  const env: Record<string, string> = { ...process.env as any, GIT_TERMINAL_PROMPT: "0" }
  let askpassDir: string | undefined
  if (opts.https) {
    askpassDir = mkdtempSync(join(tmpdir(), "mux-askpass-"))
    const script = join(askpassDir, "askpass.sh")
    // git calls askpass twice: once for Username, once for Password.
    writeFileSync(script, `#!/bin/sh\ncase "$1" in *Username*) echo "$MUX_GIT_USER";; *) echo "$MUX_GIT_TOKEN";; esac\n`)
    chmodSync(script, 0o700)
    env.GIT_ASKPASS = script
    env.MUX_GIT_USER = opts.https.user
    env.MUX_GIT_TOKEN = opts.https.token
  }
  if (opts.sshCommand) env.GIT_SSH_COMMAND = opts.sshCommand

  try {
    execFileSync("git", ["-c", "credential.helper=", "clone", opts.url, opts.targetDir],
      { env, timeout: 300_000, stdio: ["pipe", "pipe", "pipe"] })
  } catch (e: any) {
    rmSync(opts.targetDir, { recursive: true, force: true }) // remove the half-clone
    throw new Error(`${e?.stdout ?? ""}${e?.stderr ?? ""}`.trim() || "git clone failed")
  } finally {
    if (askpassDir) rmSync(askpassDir, { recursive: true, force: true })
  }
  return { localPath: opts.targetDir, reused: false }
}
