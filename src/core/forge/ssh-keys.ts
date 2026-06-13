// src/core/forge/ssh-keys.ts
import { execFileSync } from "child_process"
import { mkdirSync, existsSync, readFileSync, writeFileSync, chmodSync } from "fs"
import { join } from "path"

function keyDir(root: string, connectionId: string): string {
  const safe = connectionId.replace(/[^a-zA-Z0-9._-]/g, "_")
  const dir = join(root, safe)
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  return dir
}

export interface Keypair { privatePath: string; publicKey: string; fingerprint: string }

/** Generate (once) an ed25519 keypair for a connection. Idempotent: reuse if present. */
export function ensureKeypair(root: string, connectionId: string): Keypair {
  const dir = keyDir(root, connectionId)
  const priv = join(dir, "id_ed25519")
  if (!existsSync(priv)) {
    execFileSync("ssh-keygen", ["-t", "ed25519", "-N", "", "-C", `supermux:${connectionId}`, "-f", priv],
      { stdio: ["pipe", "pipe", "pipe"] })
    chmodSync(priv, 0o600)
  }
  const publicKey = readFileSync(`${priv}.pub`, "utf8").trim()
  const fingerprint = execFileSync("ssh-keygen", ["-lf", `${priv}.pub`], { encoding: "utf8" }).trim().split(/\s+/)[1] ?? ""
  return { privatePath: priv, publicKey, fingerprint }
}

/** Write host-key lines into the connection's known_hosts; returns the file path. */
export function seedKnownHosts(root: string, lines: string[]): string {
  const path = join(root, "known_hosts")
  writeFileSync(path, lines.join("\n") + "\n", { flag: "a" })
  return path
}

export function sshCommandFor(privateKeyPath: string, knownHostsPath: string): string {
  return `ssh -i ${privateKeyPath} -o IdentitiesOnly=yes -o UserKnownHostsFile=${knownHostsPath} -o StrictHostKeyChecking=yes`
}

export function bindSshCommand(repoPath: string, sshCommand: string): void {
  execFileSync("git", ["-C", repoPath, "config", "--local", "core.sshCommand", sshCommand])
}
