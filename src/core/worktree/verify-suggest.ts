// src/core/worktree/verify-suggest.ts
import { existsSync, readFileSync, readdirSync } from "fs"
import { join } from "path"

export interface VerifySuggestion { content: string; source: string }

const SHEBANG = "#!/usr/bin/env bash\nset -euo pipefail\n"
// commands that look like a project check (test / lint / typecheck), case-insensitive
const CHECK_RE = /\b(tests?|check|lint|type-?check|pytest|jest|vitest|rspec|tox|nox)\b|\b(cargo|go|mvn|gradle\w*|npm|pnpm|yarn|bun)\b.*\btest/i

function readSafe(p: string): string { try { return readFileSync(p, "utf-8") } catch { return "" } }

/** Pull candidate check commands out of a CI yaml's `run:` / `- <cmd>` lines (no YAML dep). */
export function extractCiCommands(yaml: string): string[] {
  const out: string[] = []
  const lines = yaml.split("\n")
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const run = line.match(/^\s*-?\s*run:\s*(.*)$/) // GitHub Actions
    if (run) {
      const val = run[1].trim()
      if (val === "|" || val === ">" || val === "|-" || val === ">-") {
        const indent = line.match(/^\s*/)![0].length
        for (let j = i + 1; j < lines.length; j++) {
          if (lines[j].trim() === "") continue
          if (lines[j].match(/^\s*/)![0].length <= indent) break
          if (CHECK_RE.test(lines[j])) out.push(lines[j].trim())
        }
      } else if (val && CHECK_RE.test(val)) out.push(val)
      continue
    }
    const dash = line.match(/^\s*-\s+(.*)$/) // GitLab `script:` entries / generic list items
    if (dash && !/^(uses|name|with|env):/.test(dash[1]) && CHECK_RE.test(dash[1])) out.push(dash[1].trim())
  }
  return [...new Set(out)].filter((c) => c.length > 1 && c.length < 200).slice(0, 10)
}

function fromCi(repoDir: string): VerifySuggestion | null {
  const files: string[] = []
  const wf = join(repoDir, ".github", "workflows")
  if (existsSync(wf)) { try { for (const f of readdirSync(wf)) if (/\.ya?ml$/.test(f)) files.push(join(wf, f)) } catch { /* unreadable */ } }
  for (const rel of [".gitlab-ci.yml", join(".circleci", "config.yml")]) if (existsSync(join(repoDir, rel))) files.push(join(repoDir, rel))
  for (const file of files) {
    const cmds = extractCiCommands(readSafe(file))
    if (cmds.length) {
      const rel = file.slice(repoDir.length + 1)
      return { content: SHEBANG + cmds.join("\n") + "\n", source: `from ${rel} (review the steps)` }
    }
  }
  return null
}

function fromManifest(repoDir: string): VerifySuggestion | null {
  const has = (f: string) => existsSync(join(repoDir, f))
  if (has("Cargo.toml")) return { content: SHEBANG + "cargo test\n", source: "Rust convention (Cargo.toml)" }
  if (has("go.mod")) return { content: SHEBANG + "go test ./...\n", source: "Go convention (go.mod)" }
  if (has("pyproject.toml") || has("pytest.ini")) return { content: SHEBANG + "pytest\n", source: "Python convention" }
  if (has("package.json")) {
    try {
      const json = JSON.parse(readSafe(join(repoDir, "package.json"))) as { scripts?: Record<string, string> }
      if (json.scripts?.test) {
        const pm = has("bun.lockb") || has("bun.lock") ? "bun" : has("pnpm-lock.yaml") ? "pnpm" : has("yarn.lock") ? "yarn" : "npm"
        return { content: SHEBANG + `${pm} test\n`, source: `package.json scripts.test (${pm})` }
      }
    } catch { /* malformed package.json */ }
  }
  if (has("Makefile") && /^test:/m.test(readSafe(join(repoDir, "Makefile")))) return { content: SHEBANG + "make test\n", source: "Makefile test target" }
  if (has("justfile") || has("Justfile")) return { content: SHEBANG + "just test\n", source: "justfile" }
  if (has("Gemfile")) return { content: SHEBANG + "bundle exec rake test\n", source: "Ruby (Gemfile)" }
  if (has("pom.xml")) return { content: SHEBANG + "mvn test\n", source: "Maven (pom.xml)" }
  if (has("build.gradle") || has("build.gradle.kts")) return { content: SHEBANG + "gradle test\n", source: "Gradle" }
  return null
}

/** Draft a `.mux/verify.sh` body + a human source label, never written here. */
export function suggestVerify(repoDir: string): VerifySuggestion {
  return fromCi(repoDir) ?? fromManifest(repoDir) ?? {
    content: SHEBANG + "# No tests detected — add your project's check command(s), e.g.:\n# npm test\n",
    source: "no tests detected — fill in the template",
  }
}
