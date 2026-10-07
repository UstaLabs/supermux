# Step 2 — auth unification

**Date:** 2026-08-08
**Program:** session-consolidation, step 2 of 6
**Spec:** docs/superpowers/specs/2026-08-07-agent-adapter-consolidation-spec.md
**Status:** plan, written before the code

## Summary

Each agent module keeps its own private auth resolver. This step makes the five
resolvers agree on a signature, a result shape, and a documented failure policy.
It also stops the credential drift that occurs when a session refreshes a token
in its own copy of the user's credential file.

The step does not add a public `AuthProvider` interface. The surface stays a set
of per-module functions, as the spec requires.

## Verified findings

1. **Refresh drift.** Codex and cursor copy the canonical credential into the
   session home. The CLI refreshes the token in that copy. The canonical file
   never learns. A long session drifts away from the user's credential, and a
   rotated refresh token can make the canonical file useless.
2. **Signature chaos.** `resolveCodexAuth` and `resolveCursorAuth` are async and
   throw. `resolveOpenCodeAuth` and `resolveGrokAuth` are sync and never throw.
   Claude has no resolver.
3. **Claude detection is in three places.** `detect.ts` probes the credential
   file. `claude-auth-status.ts` runs `claude auth status` on darwin. `main.ts`
   reads the stored settings credentials. Two call sites in `main.ts` combine
   these differently (lines 1810 and 2004).

## Design

### One result shape, five private resolvers

A new plain type alias holds the shared result shape:

```ts
export type AgentAuthResult<Mode extends string> = {
  mode: Mode
  env: Record<string, string>
}
```

Each module names its own modes and keeps its own options type. No interface
exists, and no module imports another module's resolver.

### Normalized signature

Every resolver becomes `async`. This removes the caller's need to know which
kind is synchronous. `openCodeDataDir` stays synchronous because it is a pure
path helper that `usage/index.ts` calls at module scope.

### Failure policy, per kind

| Kind | Fails closed | Reason |
| --- | --- | --- |
| codex | yes | The app-server child cannot start without a credential. |
| cursor | yes | The smoke test fails later and the session becomes a phantom. |
| opencode | no | The free `opencode/*` tier runs with no credential. |
| grok | no | Grok reports the auth error on the first turn. |
| claude | no | Claude reads the shared home; the broker only reports status. |

Each `auth.ts` states its policy in the file header. A test proves the policy
for each kind.

### Credential promotion — the drift fix

Grok points the child at the canonical file with `GROK_AUTH_PATH`. That design
is correct, but it is not provable for codex or cursor from this workstation.
The real CLI refresh flows cannot run here. A symlink is known to be wrong,
because an atomic rename replaces the symlink itself. An environment override
must be supported by the CLI, and no such variable is documented for codex or
cursor. Therefore this step does not change the transport.

The step adds **promotion on spawn and on resume**, which needs no CLI support:

1. Read the freshness of the session copy and of the canonical file.
2. If the session copy is strictly newer, and it parses, and its bytes differ,
   write it to the canonical path atomically (temp file, mode 0600, rename).
3. Copy the canonical file to the session home, as today.

Every spawn and every resume therefore heals the drift in both directions. The
codex session that refreshed its token gives that token back to the user's file,
and the next sibling session receives it.

Freshness signals, verified against the real files on this host:

- **codex** — `tokens.access_token` is a JWT with an `exp` claim.
  `~/.codex/auth.json` also holds `last_refresh`.
- **cursor** — `accessToken` is a JWT with an `exp` claim.
  `~/.config/cursor/auth.json` holds `accessToken` and `refreshToken` only.
- **grok** — `expires_at` per credential entry. Grok keeps its reader.

When either side has no readable claim, the comparison falls back to the file
modification time. `copyFileSync` gives the copy a new modification time, so a
copy always looks newer than its source. The byte-equality guard makes that case
a no-operation.

Guards, each with a test:

- A file that does not parse as JSON is never promoted.
- An empty file is never promoted.
- Identical bytes are never promoted.
- A failed promotion never removes the session copy, and never leaves a partial
  canonical file, because the write is a temp file plus a rename.

**Deliberate difference from grok:** codex and cursor do not promote when the
canonical file is absent. Grok does, because grok migrates old private copies.
For codex and cursor, an absent canonical file means the user logged out on the
host. A promotion would undo that logout. The fail-closed error stays.

The atomic write and the compare-then-promote driver live in one small module,
`src/core/agents/credential-file.ts`. It holds mechanics, not a contract: two
plain functions and one function type. Each agent module supplies its own
freshness reader, because the file dialect is the agent's own.

### Claude gains an auth module

`src/core/agents/claude/auth.ts` holds all claude detection:

- `claudeCredentialsPath(paths)` — `~/.claude/.credentials.json`.
- `claudeCliIsAuthenticated(platform, runner)` — the darwin `claude auth status`
  fallback, moved from `claude-auth-status.ts`. The keychain login writes no
  credential file, so the file probe alone reports a logged-in user as logged
  out.
- `claudeIsAuthed(opts)` — the consolidated predicate. The order is: stored or
  exported credential, then the credential file, then the darwin CLI probe.
- `resolveClaudeAuth(opts)` — the normalized async resolver. It returns
  `mode` and an empty `env`, because claude uses the broker's own home.

`detect.ts` gets a per-kind credential-probe table. Claude's entry calls
`claudeIsAuthed`. The other four keep the single file probe. The table replaces
the inline kind test, so no service holds a kind check.

`main.ts` calls `claudeIsAuthed` at both of its claude sites.
`claude-auth-status.ts` is deleted.

## Risks

- **A wrong promotion locks the fleet out.** The guards above make a promotion
  possible only when the session file parses, differs, and is newer. The write
  is atomic, so a crash cannot leave a partial canonical file.
- **Cursor refresh behavior is unverified.** If cursor-agent never refreshes its
  session copy, promotion never triggers, and the behavior equals today's.
- **A logout race.** A user logs out on the host while a session holds a valid
  copy. Codex and cursor keep the fail-closed error. Grok keeps its recovery.
- **Async resolvers change five call sites.** `tsc` finds every missed `await`,
  because the result is no longer the value the caller expects.

## Test plan

- Per resolver: the async contract, and the throw or no-throw policy.
- Promotion: newer copy wins, older copy loses, corrupt copy never clobbers,
  empty copy never clobbers, identical bytes do not rewrite, and the write is a
  temp file plus a rename.
- Claude: the env override, the file probe, and the darwin shell-out with a
  mocked runner.
- The existing grok, codex, and cursor auth suites must stay green.

## Out of scope

- The login drivers in `src/core/agents/login/` stay untouched.
- The transport for codex and cursor stays a copy. An environment override or a
  shadow home needs a verified CLI behavior, and that verification needs the
  real CLIs.
- Multi-account homes stay deferred, as the spec records.
