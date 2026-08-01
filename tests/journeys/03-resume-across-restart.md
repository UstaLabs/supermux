# 03 — Resume across a broker restart

**Gate:** merge · **Implemented:** not yet

The pitch is "sessions survive closed laptops, lost connections, broker restarts
and host reboots". Nothing currently proves the restart half of that from a
client's point of view.

## Preconditions
- Journeys 01 and 02 complete, so there is a transcript with at least one
  exchange in it.

## Steps
1. Note the visible transcript.
2. Stop the broker and start it again against the **same** state directory.
3. Leave the client alone — do not reload, re-pair or re-navigate.

## Outcomes
- The client reconnects on its own, with no user action.
- The transcript is unchanged: same messages, same order, nothing duplicated.
- The session is still listed with its previous status.
- A message sent after the restart is delivered and answered, proving the
  reconnect restored a working socket rather than just a rendered page.

## Known traps
- Assert on **content**, not a "connected" indicator — the indicator can be right
  while the stream is dead.
- Watch for a duplicated transcript: a reconnect that replays history without
  deduplicating is the failure this journey is for.
