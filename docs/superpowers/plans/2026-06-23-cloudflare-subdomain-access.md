# Cloudflare Named Tunnel — Subdomain Access — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `supermux connect` → Cloudflare → **named tunnel** actually serve the broker UI at its host (fix the bare-404), and add an opt-in wildcard-subdomain setup so `expose_port` apps get `app.<base>` instead of `/p/app/`.

**Architecture:** The 404 is caused by the named path never writing cloudflared's ingress config. We add three pure helpers + rewrite the named branch of `up()` to write `~/.cloudflared/config.yml` (broker-host ingress, optional wildcard ingress, catch-all 404) and install the service against it. A wildcard opt-in (prompt / `--wildcard` flag) also routes `*.<base>` DNS and returns a `proxyBaseDomain`, which the connect flow writes to `.env` as `MUX_PROXY_BASE_DOMAIN`. The broker already routes subdomains off that env var — **no broker code changes**.

**Tech Stack:** Bun + TypeScript, `bun:test`. All external process calls go through the injected `Run` (`ctx.run`), so every change is unit-testable with a fake Run and zero network. Env-writing is tested against a real tmp dir (existing convention in `public-url.test.ts`).

**Reference spec:** `docs/superpowers/specs/2026-06-23-cloudflare-subdomain-access-design.md`

**Commits:** End every commit message with the repo trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

**Order:** Task 1 (types) → 2 (pure helpers) → 3 (rewrite `up()`) → 4 (env writer) → 5 (CLI flags + wiring) → 6 (full green) → 7 (manual verification on a real box). Each task leaves the suite green.

---

### Task 1: Types — wildcard inputs + proxy-base-domain output

**Files:**
- Modify: `src/core/tunnels/types.ts` (`ConnectCtx`, `TunnelResult`)

Type-only change; verified by `bun run typecheck` (no unit test — later tasks exercise these fields).

- [ ] **Step 1 — add wildcard inputs to `ConnectCtx`.** In `src/core/tunnels/types.ts`, inside `interface ConnectCtx`, after the `publicUrlHint?: string` line, add:

```ts
  wildcard?: boolean // --wildcard: enable wildcard subdomains for exposed apps. undefined ⇒ prompt when interactive
  wildcardDomain?: string // --wildcard-domain: override the auto-derived wildcard base domain
```

- [ ] **Step 2 — add the proxy-base-domain output to `TunnelResult`.** In the same file, inside `interface TunnelResult`, after `notes?: string[]`, add:

```ts
  proxyBaseDomain?: string // wildcard base to persist as MUX_PROXY_BASE_DOMAIN (subdomain mode). undefined ⇒ clear it
```

- [ ] **Step 3 — typecheck.**

Run: `bun run typecheck`
Expected: exits 0, no errors.

- [ ] **Step 4 — commit.**

```bash
git add src/core/tunnels/types.ts
git commit -m "feat(tunnels): wildcard ctx inputs + proxyBaseDomain result field"
```

---

### Task 2: Pure helpers in `cloudflared.ts`

Three pure, exported functions used by the rewritten `up()`: derive the wildcard base domain, build the `config.yml` text, and parse a tunnel UUID.

**Files:**
- Modify: `src/core/tunnels/cloudflared.ts`
- Test: `src/core/tunnels/cloudflared.test.ts`

- [ ] **Step 1 — write the failing tests.** Append to `src/core/tunnels/cloudflared.test.ts` (the imports already include `cloudflaredProvider`; add the three helper names):

Change the top import line
```ts
import { cloudflaredProvider } from "./cloudflared"
```
to
```ts
import { cloudflaredProvider, baseDomainOf, buildTunnelConfig, parseTunnelId } from "./cloudflared"
```

Then append these tests at the end of the file:

```ts
// ── pure helpers ────────────────────────────────────────────────────────────────

test("baseDomainOf strips the leftmost label; a bare apex is unchanged", () => {
  expect(baseDomainOf("mux.example.com")).toBe("example.com")
  expect(baseDomainOf("example.com")).toBe("example.com")
  expect(baseDomainOf("a.b.example.com")).toBe("b.example.com")
})

test("buildTunnelConfig emits broker ingress, optional wildcard, and a catch-all", () => {
  const base = buildTunnelConfig({
    tunnelId: "t-1",
    credentialsFile: "/h/.cloudflared/t-1.json",
    port: "8787",
    host: "mux.example.com",
  })
  expect(base).toContain("tunnel: t-1")
  expect(base).toContain("credentials-file: /h/.cloudflared/t-1.json")
  expect(base).toContain("hostname: mux.example.com")
  expect(base).toContain("service: http://localhost:8787")
  expect(base).toContain("http_status:404")
  expect(base).toContain("Managed by supermux")
  expect(base).not.toContain("*.")

  const wild = buildTunnelConfig({ port: "8787", host: "mux.example.com", wildcardBase: "example.com" })
  expect(wild).toContain('hostname: "*.example.com"')
  expect(wild).toContain("tunnel: supermux") // falls back to the tunnel NAME when no id
  expect(wild).not.toContain("credentials-file:") // omitted when no creds path
})

test("parseTunnelId extracts a UUID, else undefined", () => {
  expect(parseTunnelId("Created tunnel supermux with id 11111111-2222-4333-8444-555555555555")).toBe(
    "11111111-2222-4333-8444-555555555555",
  )
  expect(parseTunnelId("no id here")).toBeUndefined()
})
```

- [ ] **Step 2 — run, verify failure.**

Run: `bun test src/core/tunnels/cloudflared.test.ts -t "baseDomainOf"`
Expected: FAIL — `baseDomainOf` (and the others) are not exported / not a function.

- [ ] **Step 3 — implement the helpers.** In `src/core/tunnels/cloudflared.ts`, add these exported helpers directly below the existing `hostFromHint` function (above `export const cloudflaredProvider`). They need no new imports — `TUNNEL_NAME` is already defined in the file (the `homedir` import is added in Task 3, where it's first used):

```ts
/**
 * Derive the wildcard base domain from a tunnel host by dropping the leftmost
 * label: "mux.example.com" → "example.com". A bare apex (≤2 labels) is returned
 * unchanged. Public-suffix edge cases (e.g. "x.example.co.uk") are why the caller
 * shows this for confirmation / allows --wildcard-domain to override it.
 */
export function baseDomainOf(host: string): string {
  const labels = host.split(".").filter(Boolean)
  return labels.length <= 2 ? host : labels.slice(1).join(".")
}

/**
 * Build cloudflared's config.yml for the named tunnel. The broker-host ingress
 * rule is the line the old flow omitted (→ the 404). A wildcard rule is added when
 * `wildcardBase` is set. The leading marker lets a re-run recognize a
 * supermux-written file (so it isn't backed up again). Falls back to the tunnel
 * NAME and omits credentials-file when the UUID couldn't be resolved — cloudflared
 * then locates the credentials by name in its default dir.
 */
export function buildTunnelConfig(opts: {
  tunnelId?: string
  credentialsFile?: string
  port: string
  host: string
  wildcardBase?: string
}): string {
  const svc = `http://localhost:${opts.port}`
  const rules = [`  - hostname: ${opts.host}\n    service: ${svc}`]
  if (opts.wildcardBase) rules.push(`  - hostname: "*.${opts.wildcardBase}"\n    service: ${svc}`)
  rules.push(`  - service: http_status:404`)
  const creds = opts.credentialsFile ? `credentials-file: ${opts.credentialsFile}\n` : ""
  return (
    `# Managed by supermux connect — re-running may overwrite this file.\n` +
    `tunnel: ${opts.tunnelId || TUNNEL_NAME}\n` +
    creds +
    `ingress:\n${rules.join("\n")}\n`
  )
}

/** Pull a tunnel UUID out of `cloudflared tunnel create` stdout (or any text). */
export function parseTunnelId(text: string): string | undefined {
  return text.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0]
}
```

- [ ] **Step 4 — run, verify pass.**

Run: `bun test src/core/tunnels/cloudflared.test.ts -t "baseDomainOf"` then `bun test src/core/tunnels/cloudflared.test.ts`
Expected: the three new tests PASS; all existing cloudflared tests still PASS.

- [ ] **Step 5 — commit.**

```bash
git add src/core/tunnels/cloudflared.ts src/core/tunnels/cloudflared.test.ts
git commit -m "feat(cloudflared): pure helpers — baseDomainOf, buildTunnelConfig, parseTunnelId"
```

---

### Task 3: Rewrite the named branch of `up()` (the 404 fix + wildcard)

Write the ingress `config.yml`, resolve the tunnel UUID (best-effort), optionally set up wildcard, then install the service.

**Files:**
- Modify: `src/core/tunnels/cloudflared.ts` (named branch of `up()`, ~lines 99-126; add a private `resolveTunnelId` helper)
- Test: `src/core/tunnels/cloudflared.test.ts`

- [ ] **Step 1 — write the failing tests.** Append to `src/core/tunnels/cloudflared.test.ts`:

```ts
// ── up(): named — ingress config + wildcard ─────────────────────────────────────

test("named: writes config.yml with the broker-host ingress rule (the 404 fix)", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" }, [
    { match: /tunnel create/, result: { stdout: "Created tunnel supermux with id 11111111-1111-4111-8111-111111111111" } },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.publicUrl).toBe("https://mux.example.com")
  expect(res.proxyBaseDomain).toBeUndefined()
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write).toBeTruthy()
  expect(write![2]).toContain("hostname: mux.example.com")
  expect(write![2]).toContain("service: http://localhost:8787")
  expect(write![2]).toContain("http_status:404")
  expect(write![2]).toContain("11111111-1111-4111-8111-111111111111.json")
  expect(write![2]).not.toContain("*.")
  expect(calls).toContainEqual(["cloudflared", "tunnel", "route", "dns", "supermux", "mux.example.com"])
  expect(calls).toContainEqual(["cloudflared", "service", "install"])
})

test("named: --wildcard routes *.base and adds a wildcard ingress rule, returns proxyBaseDomain", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com", wildcard: true }, [
    { match: /tunnel create/, result: { stdout: "id 22222222-2222-4222-8222-222222222222" } },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.proxyBaseDomain).toBe("example.com")
  expect(calls).toContainEqual(["cloudflared", "tunnel", "route", "dns", "supermux", "*.example.com"])
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write![2]).toContain('hostname: "*.example.com"')
})

test("named: a --wildcard-domain overrides the derived base", async () => {
  const { ctx, calls } = makeCtx(
    { mode: "named", publicUrlHint: "mux.example.com", wildcard: true, wildcardDomain: "apps.example.com" },
    [{ match: /tunnel create/, result: { stdout: "id 22222222-2222-4222-8222-222222222222" } }],
  )
  const res = await cloudflaredProvider.up(ctx)
  expect(res.proxyBaseDomain).toBe("apps.example.com")
  expect(calls).toContainEqual(["cloudflared", "tunnel", "route", "dns", "supermux", "*.apps.example.com"])
})

test("named: wildcard DNS failure keeps the broker host working and skips proxyBaseDomain", async () => {
  const { ctx, calls, out } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com", wildcard: true }, [
    { match: /tunnel create/, result: { stdout: "id 33333333-3333-4333-8333-333333333333" } },
    { match: /route dns supermux \*\./, result: { code: 1, stderr: "wildcard not allowed on this plan" } },
  ])
  const res = await cloudflaredProvider.up(ctx)
  expect(res.proxyBaseDomain).toBeUndefined()
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write![2]).not.toContain("*.example.com")
  expect(out.join("\n")).toContain("path mode")
})

test("named: resolves the tunnel id from `tunnel list` when create says it already exists", async () => {
  const { ctx, calls } = makeCtx({ mode: "named", publicUrlHint: "mux.example.com" }, [
    { match: /tunnel create/, result: { code: 1, stderr: "tunnel with name supermux already exists" } },
    { match: /tunnel list --output json/, result: { stdout: '[{"id":"44444444-4444-4444-4444-444444444444","name":"supermux"}]' } },
  ])
  await cloudflaredProvider.up(ctx)
  const write = calls.find((c) => c[0] === "sh" && c[2]!.includes("config.yml"))
  expect(write![2]).toContain("credentials-file:")
  expect(write![2]).toContain("44444444-4444-4444-4444-444444444444.json")
})
```

- [ ] **Step 2 — run, verify failure.**

Run: `bun test src/core/tunnels/cloudflared.test.ts -t "the 404 fix"`
Expected: FAIL — no `sh … config.yml` call exists yet (the old `up()` never writes a config).

- [ ] **Step 3 — add the `resolveTunnelId` helper.** In `src/core/tunnels/cloudflared.ts`, add this private helper directly above `export const cloudflaredProvider` (it uses `ctx.run`, so it is not exported):

```ts
/**
 * Best-effort tunnel UUID: prefer the id printed by `tunnel create`; on a re-run
 * ("already exists" ⇒ no id printed) fall back to `tunnel list --output json`.
 * Returns undefined if neither yields one (caller then writes config by name).
 */
async function resolveTunnelId(ctx: ConnectCtx, createOut: string): Promise<string | undefined> {
  const fromCreate = parseTunnelId(createOut)
  if (fromCreate) return fromCreate
  const r = await ctx.run(["cloudflared", "tunnel", "list", "--output", "json"])
  try {
    const list = JSON.parse(r.stdout) as Array<{ id?: string; name?: string }>
    return list.find((t) => t.name === TUNNEL_NAME)?.id
  } catch {
    return undefined
  }
}
```

- [ ] **Step 4 — replace the named branch of `up()`.** First add `import { homedir } from "os"` to the top of `src/core/tunnels/cloudflared.ts` (the new code uses `homedir()`). Then replace the entire `if (mode.id === "named") { … }` block (currently ~lines 99-126, ending with `return { publicUrl: \`https://${host}\`, stable: true }`) with:

```ts
    if (mode.id === "named") {
      // Hostname: prefer the user-supplied hint, else ask. (No TTY ⇒ ask ⇒ null.)
      const host =
        hostFromHint(ctx.publicUrlHint) ??
        hostFromHint((await ctx.ask("Hostname for the tunnel (e.g. mux.yourdomain.com): ")) ?? undefined)
      if (!host) throw new Error("a hostname is required for a named cloudflared tunnel")

      // 1. Create the tunnel (tolerate a re-run) and learn its UUID (best-effort).
      const created = await ctx.run(["cloudflared", "tunnel", "create", TUNNEL_NAME])
      if (created.code !== 0 && !/already exists/i.test(created.stdout + created.stderr)) {
        throw new Error(`cloudflared tunnel create failed: ${created.stderr || created.stdout}`)
      }
      const tunnelId = await resolveTunnelId(ctx, created.stdout)

      // 2. Decide wildcard subdomains for exposed apps. --wildcard forces it on;
      //    otherwise prompt only when interactive (never under --yes / no-TTY).
      const base = ctx.wildcardDomain || baseDomainOf(host)
      let wildcardBase: string | undefined
      if (ctx.wildcard === true) {
        wildcardBase = base
      } else if (ctx.wildcard === undefined && ctx.tty && !ctx.yes) {
        if (await ctx.confirm(`Also expose apps on their own subdomains under *.${base}? `, false)) {
          wildcardBase = base
        }
      }

      // 3. Route DNS for the broker host, plus the wildcard when requested. A
      //    wildcard-DNS failure (plan limits) must NOT break the broker host — drop
      //    back to path mode and clear the base domain.
      await ctx.run(["cloudflared", "tunnel", "route", "dns", TUNNEL_NAME, host])
      if (wildcardBase) {
        const wr = await ctx.run(["cloudflared", "tunnel", "route", "dns", TUNNEL_NAME, `*.${wildcardBase}`])
        if (wr.code !== 0) {
          ctx.println(
            `Wildcard DNS (*.${wildcardBase}) failed: ${wr.stderr || wr.stdout}. ` +
              `Exposed apps stay on path mode (/p/<slug>/); the broker UI is unaffected.`,
          )
          wildcardBase = undefined
        }
      }

      // 4. Write the ingress config (the rule the old flow omitted → the 404). Back
      //    up a pre-existing NON-supermux config.yml before overwriting it.
      const home = process.env.HOME || homedir()
      const cfgDir = `${home}/.cloudflared`
      const cfgPath = `${cfgDir}/config.yml`
      const yaml = buildTunnelConfig({
        tunnelId,
        credentialsFile: tunnelId ? `${cfgDir}/${tunnelId}.json` : undefined,
        port: ctx.port,
        host,
        wildcardBase,
      })
      await ctx.run([
        "sh",
        "-c",
        `mkdir -p "${cfgDir}"; ` +
          `if [ -f "${cfgPath}" ] && ! grep -q "Managed by supermux" "${cfgPath}"; then cp "${cfgPath}" "${cfgPath}.bak"; fi; ` +
          `cat > "${cfgPath}" <<'SUPERMUX_CFG'\n${yaml}SUPERMUX_CFG\n`,
      ])

      // 5. Install it as a persistent OS service (reads the config above). Best-
      //    effort: on failure, hand the user the manual run command.
      const svc = await ctx.run(["cloudflared", "service", "install"])
      if (svc.code !== 0) {
        ctx.println("Couldn't install cloudflared as a service. Run it yourself (keep it running):")
        ctx.println(`  cloudflared tunnel run ${TUNNEL_NAME}`)
      }

      const result: TunnelResult = { publicUrl: `https://${host}`, stable: true }
      if (wildcardBase) result.proxyBaseDomain = wildcardBase
      return result
    }
```

(Assign `proxyBaseDomain` only when set — keeps the no-wildcard result shape identical to the old `{ publicUrl, stable }`, so the existing `toEqual` test stays green. `TunnelResult` is already imported in `cloudflared.ts`.)

- [ ] **Step 5 — run, verify pass (new + existing).**

Run: `bun test src/core/tunnels/cloudflared.test.ts`
Expected: all PASS — the five new named tests AND every pre-existing cloudflared test (the existing named tests don't supply a UUID; `resolveTunnelId` returns undefined, `buildTunnelConfig` falls back to `tunnel: supermux`, and the assertions there use `toContainEqual`, so the extra `tunnel list` + `sh` calls don't break them).

- [ ] **Step 6 — typecheck + commit.**

```bash
bun run typecheck
git add src/core/tunnels/cloudflared.ts src/core/tunnels/cloudflared.test.ts
git commit -m "fix(cloudflared): write ingress config for named tunnels (+ opt-in wildcard)"
```

---

### Task 4: Env writer sets/clears `MUX_PROXY_BASE_DOMAIN`

**Files:**
- Modify: `src/core/tunnels/public-url.ts` (`writeEnvPublicUrl`)
- Test: `src/core/tunnels/public-url.test.ts`

- [ ] **Step 1 — write the failing tests.** Append to `src/core/tunnels/public-url.test.ts` (its imports already include `writeFileSync`, `readFileSync`, `join`):

```ts
test("writeEnvPublicUrl sets MUX_PROXY_BASE_DOMAIN when a base domain is given", () => {
  const dir = tmp()
  writeEnvPublicUrl(dir, "8787", "https://mux.example.com", "example.com")
  const env = readFileSync(join(dir, ".env"), "utf8")
  expect(env).toContain("MUX_PROXY_BASE_DOMAIN=example.com")
})

test("writeEnvPublicUrl clears a stale MUX_PROXY_BASE_DOMAIN when none is given, keeps other keys", () => {
  const dir = tmp()
  writeFileSync(join(dir, ".env"), "MUX_PROXY_BASE_DOMAIN=old.example.com\nFOO=bar\n")
  writeEnvPublicUrl(dir, "8787", "http://localhost:8787")
  const env = readFileSync(join(dir, ".env"), "utf8")
  expect(env).not.toContain("MUX_PROXY_BASE_DOMAIN")
  expect(env).toContain("FOO=bar")
})
```

- [ ] **Step 2 — run, verify failure.**

Run: `bun test src/core/tunnels/public-url.test.ts -t "MUX_PROXY_BASE_DOMAIN"`
Expected: FAIL — `writeEnvPublicUrl` ignores the 4th arg and never sets/clears the key.

- [ ] **Step 3 — implement.** In `src/core/tunnels/public-url.ts`, change the `writeEnvPublicUrl` signature and body. Replace:

```ts
export function writeEnvPublicUrl(stateDir: string, port: string, url: string): void {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  const envPath = join(stateDir, ".env")
  const map = existsSync(envPath) ? parseEnvFile(readFileSync(envPath, "utf8")) : new Map<string, string>()
  map.set("MUX_WEB_PORT", port)
  map.set("MUX_WEB_PUBLIC_URL", url)
  writeFileSync(envPath, serializeEnv(map))
  chmodSync(envPath, 0o600)
}
```

with:

```ts
export function writeEnvPublicUrl(
  stateDir: string,
  port: string,
  url: string,
  proxyBaseDomain?: string,
): void {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  const envPath = join(stateDir, ".env")
  const map = existsSync(envPath) ? parseEnvFile(readFileSync(envPath, "utf8")) : new Map<string, string>()
  map.set("MUX_WEB_PORT", port)
  map.set("MUX_WEB_PUBLIC_URL", url)
  // Wildcard (subdomain) mode is env-driven: set it when present, clear a stale
  // value otherwise (so `connect --off` / a re-run without wildcard can't leave a
  // base domain that silently breaks routing).
  if (proxyBaseDomain) map.set("MUX_PROXY_BASE_DOMAIN", proxyBaseDomain)
  else map.delete("MUX_PROXY_BASE_DOMAIN")
  writeFileSync(envPath, serializeEnv(map))
  chmodSync(envPath, 0o600)
}
```

Also update the JSDoc above the function: add a sentence — "A 4th `proxyBaseDomain` sets `MUX_PROXY_BASE_DOMAIN` (subdomain mode); omitting it CLEARS any stale value."

- [ ] **Step 4 — run, verify pass.**

Run: `bun test src/core/tunnels/public-url.test.ts`
Expected: the two new tests PASS; the existing `writeEnvPublicUrl` tests still PASS (they pass 3 args ⇒ no base domain ⇒ the key is simply absent, which they already expect).

- [ ] **Step 5 — commit.**

```bash
git add src/core/tunnels/public-url.ts src/core/tunnels/public-url.test.ts
git commit -m "feat(tunnels): writeEnvPublicUrl sets/clears MUX_PROXY_BASE_DOMAIN"
```

---

### Task 5: CLI flags + wiring (`cli-connect.ts`)

Parse `--wildcard` / `--wildcard-domain`, thread them into `ConnectCtx`, and pass the provider's `proxyBaseDomain` into the env writer.

**Files:**
- Modify: `src/cli-connect.ts` (`Flags`, `parseFlags`, `ConnectCtx` build, `connectProvider`, `HELP`)
- Test: `src/cli-connect.test.ts`

- [ ] **Step 1 — write the failing tests.** In `src/cli-connect.test.ts`, first extend the `fs` import — change:

```ts
import { mkdtempSync, readFileSync, existsSync } from "fs"
```
to
```ts
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from "fs"
```

Then append these tests:

```ts
test("cloudflared proxyBaseDomain is written to .env as MUX_PROXY_BASE_DOMAIN", async () => {
  const dir = tmp()
  const p = fake({
    async up() {
      return { publicUrl: "https://mux.example.com", stable: true, proxyBaseDomain: "example.com" }
    },
  })
  await runConnectCommand(["cloudflared", "--yes"], {
    providers: [p], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(readFileSync(join(dir, ".env"), "utf8")).toContain("MUX_PROXY_BASE_DOMAIN=example.com")
})

test("--wildcard is parsed and passed to the provider as ctx.wildcard", async () => {
  const dir = tmp()
  let seen: boolean | undefined
  const p = fake({
    async up(ctx) {
      seen = ctx.wildcard
      return { publicUrl: "https://mux.example.com", stable: true }
    },
  })
  await runConnectCommand(["cloudflared", "--yes", "--wildcard"], {
    providers: [p], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(seen).toBe(true)
})

test("--wildcard-domain is parsed and passed to the provider as ctx.wildcardDomain", async () => {
  const dir = tmp()
  let seen: string | undefined
  const p = fake({
    async up(ctx) {
      seen = ctx.wildcardDomain
      return { publicUrl: "https://mux.example.com", stable: true }
    },
  })
  await runConnectCommand(["cloudflared", "--yes", "--wildcard-domain", "apps.example.com"], {
    providers: [p], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(seen).toBe("apps.example.com")
})

test("--off clears a stale MUX_PROXY_BASE_DOMAIN", async () => {
  const dir = tmp()
  writeFileSync(join(dir, ".env"), "MUX_PROXY_BASE_DOMAIN=stale.example.com\n")
  await runConnectCommand(["--off", "--port", "8787"], {
    providers: [fake()], stateDir: dir, tty: false, run: okRun, println() {},
  })
  expect(readFileSync(join(dir, ".env"), "utf8")).not.toContain("MUX_PROXY_BASE_DOMAIN")
})
```

- [ ] **Step 2 — run, verify failure.**

Run: `bun test src/cli-connect.test.ts -t "wildcard"`
Expected: FAIL — `--wildcard` is an unknown flag (ignored), so `ctx.wildcard` is undefined; and `proxyBaseDomain` isn't forwarded to the env writer.

- [ ] **Step 3 — add the flags to the `Flags` interface.** In `src/cli-connect.ts`, inside `interface Flags`, after `publicUrl?: string`, add:

```ts
  wildcard?: boolean
  wildcardDomain?: string
```

- [ ] **Step 4 — parse the flags.** In `parseFlags`, in the `switch (a)`, add these two cases (next to `--public-url`):

```ts
      case "--wildcard": f.wildcard = true; break
      case "--wildcard-domain": f.wildcardDomain = args[++i]; break
```

- [ ] **Step 5 — thread into `ConnectCtx`.** In `runConnectCommand`, where the `ctx: ConnectCtx = { … }` object is built, add after `publicUrlHint: flags.publicUrl,`:

```ts
    wildcard: flags.wildcard,
    wildcardDomain: flags.wildcardDomain,
```

- [ ] **Step 6 — forward `proxyBaseDomain` to the env writer.** In `connectProvider`, change:

```ts
  writeEnvPublicUrl(ctx.stateDir, ctx.port, result.publicUrl)
```
to
```ts
  writeEnvPublicUrl(ctx.stateDir, ctx.port, result.publicUrl, result.proxyBaseDomain)
```

(`disconnect()` already calls `writeEnvPublicUrl(ctx.stateDir, ctx.port, local)` with three args ⇒ `proxyBaseDomain` undefined ⇒ the key is cleared on `--off`. No change needed there — the `--off` test covers it.)

- [ ] **Step 7 — document the flags in `HELP`.** In the `HELP` template string, under `Flags:`, after the `--public-url` line, add:

```
  --wildcard        (cloudflared named) also expose apps on *.<base> subdomains
  --wildcard-domain <d>  override the auto-derived wildcard base domain
```

- [ ] **Step 8 — run, verify pass.**

Run: `bun test src/cli-connect.test.ts`
Expected: the four new tests PASS; all existing cli-connect tests still PASS.

- [ ] **Step 9 — typecheck + commit.**

```bash
bun run typecheck
git add src/cli-connect.ts src/cli-connect.test.ts
git commit -m "feat(connect): --wildcard / --wildcard-domain flags + persist MUX_PROXY_BASE_DOMAIN"
```

---

### Task 6: Whole-suite + typecheck green

**Files:** none (verification only).

- [ ] **Step 1 — run the full test suite.**

Run: `bun test`
Expected: all tests PASS, 0 fail.

- [ ] **Step 2 — typecheck the whole project.**

Run: `bun run typecheck`
Expected: exits 0, no errors.

- [ ] **Step 3 — if anything is red, fix it before proceeding.** No commit if nothing changed; otherwise commit the fix with a clear message.

---

### Task 7: Manual verification on a real box (cannot be unit-tested)

The unit tests prove we generate the right config + commands; they cannot prove cloudflared actually serves traffic. Verify end-to-end on a machine with `cloudflared` installed and a domain on Cloudflare. This is the acceptance test for the original bug.

**Files:** none.

- [ ] **Step 1 — run the named connect flow.**

Run: `supermux connect cloudflared --public-url https://mux.<yourdomain>`
Expected: it logs in (browser), creates/keeps the `supermux` tunnel, routes DNS, writes `~/.cloudflared/config.yml`, installs the service, prints `✔ Public URL: https://mux.<yourdomain>` and a pair link.

- [ ] **Step 2 — confirm the config was written with ingress.**

Run: `cat ~/.cloudflared/config.yml`
Expected: contains `ingress:`, a `- hostname: mux.<yourdomain>` rule → `service: http://localhost:8787`, and the catch-all `- service: http_status:404`.

- [ ] **Step 3 — confirm the broker UI loads at the host (the bug is fixed).**

Run: `curl -sS -o /dev/null -w "%{http_code}\n" https://mux.<yourdomain>/`
Expected: `200` (or a normal redirect), **not** `404`. Open the URL in a browser → the supermux UI loads and pairs.

- [ ] **Step 4 — (optional) verify wildcard.** Re-run with the wildcard opt-in:

Run: `supermux connect cloudflared --public-url https://mux.<yourdomain> --wildcard`
Then in a session expose a port and check the printed URL is `https://<slug>.<yourdomain>` and loads:
```bash
# in a session: expose_port 3000  → returns https://<slug>.<yourdomain>
curl -sS -o /dev/null -w "%{http_code}\n" https://<slug>.<yourdomain>/
```
Expected: `.env` now has `MUX_PROXY_BASE_DOMAIN=<yourdomain>`; the exposed app answers on its subdomain. If your Cloudflare plan rejects the wildcard DNS, the flow prints the path-mode fallback message and the broker host still works.

- [ ] **Step 5 — report results** (paste the `config.yml`, the HTTP codes, and any wildcard outcome) back into the session for review.

---

## Self-Review

- **Spec coverage:** Part A (ingress config / 404 fix) → Tasks 2-3. Part B (wildcard prompt + `--wildcard` + `*.base` DNS + wildcard ingress + `MUX_PROXY_BASE_DOMAIN`) → Tasks 1, 3, 4, 5. Env set/clear (incl. `--off`) → Tasks 4-5. Edge cases: wildcard-DNS-failure fallback (Task 3, test + impl), foreign `config.yml` backup (Task 3 impl), re-run/"already exists" UUID resolution (Tasks 2-3), home-path expansion (Task 3 uses resolved `home`), cookie re-scope (handled by the existing re-pair — no code), `service install` failure fallback (preserved in Task 3). Manual end-to-end → Task 7. No broker-side change needed (spec confirms `main.ts` already reads `MUX_PROXY_BASE_DOMAIN`). The dormant `wildcardBaseDomain` store field is explicitly Out of Scope (spec) — not touched.
- **Placeholder scan:** none — every code step has complete code; `<yourdomain>` / `<slug>` in Task 7 are user-substituted runtime values, not plan gaps.
- **Type consistency:** `ConnectCtx.wildcard?: boolean`, `ConnectCtx.wildcardDomain?: string`, `TunnelResult.proxyBaseDomain?: string` (Task 1) are used consistently in `up()` (Task 3) and `cli-connect.ts` (Task 5). `buildTunnelConfig`/`baseDomainOf`/`parseTunnelId` signatures (Task 2) match their call sites (Task 3). `writeEnvPublicUrl(stateDir, port, url, proxyBaseDomain?)` (Task 4) matches the call in `connectProvider` (Task 5).
