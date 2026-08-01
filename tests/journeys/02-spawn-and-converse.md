# 02 — Spawn and converse

**Gate:** merge · **Implemented:** `tests/ui/core-journey.spec.ts`, `.maestro/pair-and-converse.yaml`

A message typed on a phone reaches an agent on the host and its reply comes back.
This is the product in one sentence, and it is the journey that exercises the
whole chain — client, WebSocket, broker, shim socket, agent — rather than any
single link in it.

## Preconditions
- Journey 01 complete: the client is paired and showing `session-list`.
- A seeded session bound to a deterministic fake agent that replies
  `Fixture reply: <prompt>` (`scripts/test-agent.ts`).

## Steps
1. Open the seeded session from the list.
2. Type a unique prompt into **`composer-input`**.
3. Send it with **`composer-submit`**.

## Outcomes
- **`chat-view`** is visible after opening the session.
- The user's own message renders in the transcript.
- The agent's reply — `Fixture reply: <prompt>` — renders too. **Asserting the
  reply, not the echo, is the point**: an echoed message only proves the client
  can draw its own text.
- Both messages are readable back from the broker afterwards, so the transcript
  survived the round trip rather than just appearing on screen.

## Known traps
- Use a **unique** prompt per run (timestamp). A fixed string passes against a
  stale transcript from an earlier run.
- On a device, the soft keyboard covers the submit button; dismiss it before
  tapping.
