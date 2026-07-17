# Relay Origin Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow cookie-authenticated browser mutations from both the configured direct origin and the broker's live hosted-relay origin without trusting arbitrary origins.

**Architecture:** Extend the pure origin-check helper to compare the request origin against an explicit list of trusted URLs. At the web-channel boundary, supply the configured `publicUrl` and the live relay URL already exposed by `getRelayUrl()`, preserving the existing bearer and missing-origin paths.

**Tech Stack:** TypeScript, Bun, `bun:test`, `WebChannel` HTTP integration tests.

---

### Task 1: Reproduce and fix relay-origin rejection

**Files:**
- Modify: `src/channels/web/host-route-wiring.test.ts`
- Modify: `src/channels/web/cookies.ts`
- Modify: `src/channels/web/index.ts:1139-1149`

- [ ] **Step 1: Write the failing integration test**

Append this test to `src/channels/web/host-route-wiring.test.ts`:

```ts
test("cookie-authenticated mutation accepts the live relay origin but rejects unrelated origins", async () => {
  const relayUrl = "https://h-live.relay.supermux.dev"
  const made = makeChannel({
    publicUrl: "http://localhost:8787",
    getRelayUrl: () => relayUrl,
  })
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("admin").token

  const postDevice = (origin: string, name: string) => fetch(`${base()}/devices`, {
    method: "POST",
    headers: {
      Cookie: `cmux_token=${token}`,
      Origin: origin,
      "content-type": "application/json",
    },
    body: JSON.stringify({ name }),
  })

  expect((await postDevice(relayUrl, "relay-device")).status).toBe(200)
  expect((await postDevice("https://evil.example", "evil-device")).status).toBe(403)
})
```

- [ ] **Step 2: Run the focused test to verify it fails for the reported reason**

Run:

```bash
bun test src/channels/web/host-route-wiring.test.ts --test-name-pattern "cookie-authenticated mutation"
```

Expected: FAIL because the relay-origin request returns 403 instead of 200. The unrelated-origin assertion must not be the failing assertion.

- [ ] **Step 3: Extend the pure origin helper to accept an explicit allowlist**

Replace `sameOriginOk` in `src/channels/web/cookies.ts` with:

```ts
export function sameOriginOk(req: Request, ...publicUrls: (string | undefined)[]): boolean {
  const origin = req.headers.get("origin")
  if (!origin) return true
  try {
    const requestOrigin = new URL(origin).origin
    return publicUrls.some((publicUrl) => {
      if (!publicUrl) return false
      try {
        return requestOrigin === new URL(publicUrl).origin
      } catch {
        return false
      }
    })
  } catch {
    return false
  }
}
```

- [ ] **Step 4: Supply both direct and relay URLs at the CSRF boundary**

Change the mutating-request guard in `src/channels/web/index.ts` to:

```ts
if (!authedViaBearer(req) && !sameOriginOk(
  req,
  this.opts.publicUrl,
  this.getRelayUrl?.() ?? this.relayUrl,
)) {
  return new Response("bad origin", { status: 403 })
}
```

- [ ] **Step 5: Run focused tests to verify the fix and preserved behavior**

Run:

```bash
bun test src/channels/web/host-route-wiring.test.ts tests/web-cookies.test.ts
```

Expected: all tests PASS, including relay acceptance, unrelated-origin rejection, configured direct-origin acceptance, and missing-origin behavior.

- [ ] **Step 6: Commit the implementation**

```bash
git add src/channels/web/host-route-wiring.test.ts src/channels/web/cookies.ts src/channels/web/index.ts
git commit -m "fix(web): accept hosted relay origin" \
  -m "Co-Authored-By: GPT-5 - Codex in Supermux <noreply@openai.com>"
```

### Task 2: Verify the broker regression fix

**Files:**
- Verify only: `src/channels/web/host-route-wiring.test.ts`
- Verify only: `tests/web-cookies.test.ts`
- Verify only: `src/channels/web/cookies.ts`
- Verify only: `src/channels/web/index.ts`

- [ ] **Step 1: Run formatting and whitespace validation**

Run:

```bash
git diff --check HEAD^
```

Expected: exit code 0 with no output.

- [ ] **Step 2: Run the broker type check**

Run:

```bash
bun run typecheck
```

Expected: exit code 0 with no TypeScript errors.

- [ ] **Step 3: Run the complete broker test suite**

Run:

```bash
bun test
```

Expected: exit code 0 with all tests passing.

- [ ] **Step 4: Inspect the final scoped diff**

Run:

```bash
git show --stat --oneline HEAD
git status --short --branch
```

Expected: the implementation commit contains only the three files in Task 1, and the worktree is clean.
