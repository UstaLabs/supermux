# 01 — Pair a device

**Gate:** merge · **Implemented:** `tests/ui/core-journey.spec.ts`, `.maestro/pair-and-converse.yaml`

A device with no credentials becomes a device that can see this host's sessions.
Everything else in the product is behind this, so it is the first journey and the
one whose breakage is most invisible — a broken pairing link still returns a
perfectly valid-looking page.

## Preconditions
- A broker on throwaway state with no paired devices (`scripts/test-broker.sh`).
- One seeded session so the list has something in it.
- A freshly minted pairing token.

## Steps
1. Open the pairing link (`<base>/pair?t=<token>`) — the target of a scanned QR
   code. On a native client without a browser, paste the same link into the
   connect screen's "paste a pairing link" field.
2. If the host is unknown, confirm the trust-on-first-connect prompt.

## Outcomes
- The client lands on **`session-list`** without any further input.
- The seeded session is present as **`session-row`** (web pairs it with
  `data-session-id`; native uses `session-row:<id>`).
- The credential is stored the way that client stores it — an HttpOnly cookie on
  web, so the token never has to be presented again.
- A subsequent authenticated request succeeds, proving the credential is real and
  not merely displayed.

## Known traps
- **A document navigation to `/pair?t=…` must be answered by the server, not the
  SPA shell.** The handler verifies the token, sets the cookie and redirects; the
  SPA can do none of that. This regressed once and returned a valid 200 the whole
  time. Pinned by `src/channels/web/spa-navigation.test.ts`.
- **On Android the link must survive onboarding.** It is read by the connect
  page, so a fresh install has to open there rather than on the first carousel
  page, or the intent is consumed and gone.
