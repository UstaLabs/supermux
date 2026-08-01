# Journeys

Each `NN-name.md` here is one user journey in plain English: preconditions, the
steps a person takes, and outcomes a machine can check. They are the source the
executable specs are written from — `tests/ui/*.spec.ts` (browser) and
`.maestro/*.yaml` (device) — not documentation written after the fact.

## Why prose at all

The same journey exists four times over: web, Android, iOS, desktop. Written
directly as code, each implementation quietly becomes its own slightly different
story, and nobody can tell whether the Android suite and the Playwright suite are
even testing the same thing. One prose source keeps them honest, and it is the
artifact an agent can be handed.

Every step names elements by their **shared test id** (`src/shared/test-ids.ts`),
never by a platform-specific tag. `tests/test-ids-parity.test.ts` fails the build
if those ids drift apart across languages.

## How agents fit in

Four lanes, and only the first two touch anything that merges.

**A — agent as author.** Hand an agent one journey file, a hermetic broker
(`scripts/test-broker.sh`), and Playwright MCP or Maestro MCP. It explores the
real UI and emits a spec or a flow.

> **Acceptance rule: the generated script must pass three clean consecutive
> replays with no agent involved before it is committed.**

After that it is an ordinary file. **CI never calls a model.** This is the whole
point: agents are good at writing the script and bad at being the gate.

**B — agent as healer.** On a red run, an agent gets the Playwright trace or the
captured device state (`scripts/lib/maestro-run.sh` dumps hierarchy, screenshot
and focused window on failure) and opens a PR with a selector fix or a defect
report. A human merges it. Never auto-merged.

**C — agent as explorer.** Nightly, non-blocking. Fixed model version,
temperature 0, hard action and wall-clock budget, isolated state. Free-roam
hunting for crashes, stuck spinners, dead buttons, broken layouts. **A finding is
only filed once a deterministic replay or a server-state assertion reproduces
it.** This is where agents genuinely beat scripted tests — they find what nobody
thought to write a test for.

**D — LLM as screenshot triage.** Pass/fail stays on a pixel or perceptual
threshold. A model only sorts diffs into "intended restyle" and "layout broke" to
cut review noise. It never decides the gate.

### Why agents are kept out of the merge gate

Nondeterministic action selection, model-version drift changing behaviour
underneath you, minutes of latency per run, and — specific to this product — a
prompt-injection surface, because the app renders untrusted agent output and an
LLM driving that UI is a live target. A gate that is 90% reliable is worse than
no gate: people learn to re-run it instead of reading it.

## Status

| # | Journey | Gate | Browser | Device |
|---|---|---|---|---|
| 01 | Pair a device | merge | ✅ | ✅ |
| 02 | Spawn and converse | merge | ✅ | ✅ |
| 03 | Resume across a broker restart | merge | ⬜ | ⬜ |
| 04 | Review a diff and merge | merge | ⬜ | ⬜ |
| 05–10 | terminal · attachments · push · fleet · voice · editor | nightly | ⬜ | ⬜ |

01 and 02 are covered together by `tests/ui/core-journey.spec.ts` and
`.maestro/pair-and-converse.yaml`.
