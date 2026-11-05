# Streaming flow: the ink edge (card #76)

Ahmet tried streaming replies live and said "the flow of the words coming in can be better". We measured what he saw,
prototyped pacing options on the web (one recorded Opus stream replayed side by side), and he picked **B3 · Ink
edge** (without B4's dot). This spec turns that prototype into the app. Desktop first; Android, iOS and web follow
the same code once he approves the desktop build.

Prototype: `~/streaming-probe/flow-web/index.html` (option `b3`). Measurements and the option clips: #76 comments.

## What he saw (the problem)

Opus streams about 190 chars/s. After the broker's 50 ms batches the app redraws about 17 times a second, 10 chars
at a time (up to 39). One tick in six is empty, so the text stalls for up to 150 ms and then jumps. 36% of the
updates end mid-word. The view jumps a whole line at a time. Other agents are lumpier: Haiku drops 180–257 chars at
once, and Grok and Antigravity stall for 300 ms and then drop about 100.

## Behaviour

1. **Steady pace.** A display clock, separate from arrival, owns how much of the live text is shown. Each frame its
   head moves toward what has arrived, covering `1 - exp(-dt / 150 ms)` of the backlog, and at least 30 chars/s. Once
   the stream has ended the constant drops to 75 ms. A burst spreads over about 150 ms; a trickle flows at the
   model's own pace; the head trails arrival by about 150 ms.
2. **Whole words.** While the stream is open, the head only moves through text that is followed by whitespace. The
   trailing run of letters may be a half word, so it waits for the next batch. Two exceptions: a run longer than 32
   chars (long tokens, CJK) and the end of the stream.
3. **Ink edge.** The text is laid out up to the end of the word the head is in, so a word never wraps in view. A
   character's opacity is the larger of:
   - its distance behind the head over 24 chars, `clamp((head - i) / 24, 0, 1)`;
   - its age on screen, `easeOutCubic(age / 450 ms)`, so a pause never leaves a faded tail.

   Characters at or past the head are transparent.
4. **Gliding scroll.** While the reader follows the end, the transcript eases toward it as the live reply grows. Each
   frame covers `1 - exp(-dt / 90 ms)` of the distance, instead of jumping a line at a time.
5. **The end.** When the stored message arrives, it takes the live row's place as today: same key, so no jump. If
   its stream is still revealing, the stored row finishes that reveal (the end constant, about 0.2 s) before it
   draws statically. The last words never pop in.
6. **Reduced motion** (OS setting): this is option A. Whole words at the same steady pace, no edge, and the scroll
   jumps.

Thinking rows keep today's behaviour in this card.

## Units

| Unit | Where | Does | Depends on |
|---|---|---|---|
| `RevealClock` | `shared/commonMain/.../chat/RevealClock.kt` | Pure pacing state: `advance(nowMs, text, done)`, `head`, `laidOut`, `alphaAt(i, nowMs)`, `animating`. A `reduced` flag switches it to option A. | nothing |
| `blockSourceEnds` | same file | For a block list and its source, where each text block's raw text ends in the source. This maps a block's characters to source indices from its end. | `MdBlock` |
| `LiveReveal` | `ui/commonMain/.../chat/LiveReveal.kt` | `LiveRevealState` (the clock, plus snapshot state that draws read), `rememberLiveReveal`, the frame loop (runs only while animating, so tests reach idle), and `Modifier.inkEdge`. | `RevealClock` |
| `MarkdownBody(reveal = …)` | `ui/.../chat/Markdown.kt` | Optional. When given, text blocks (prose, heading, list item, quote) pass their layout to `inkEdge`. Code, tables and images just follow the pace. `MdText` gains an `onTextLayout` passthrough. | `LiveReveal` |
| `LocalReduceMotion` | `ui/.../theme` | A CompositionLocal, default false. Desktop provides it from the OS: macOS `defaults read com.apple.universalaccess reduceMotion`, GNOME `enable-animations`; Windows stays false. | — |
| Reveal registry | `ChatPanel.kt` | `remember(session.id)` map from stream to `LiveRevealState`. The live row and the stored row with the same stream share one reveal; the entry drops when it finishes. | `LiveReveal` |
| Glide | `ChatPanel.kt` autoscroll | One coroutine eases toward the end while following and the last row is revealing. The positions it passes are marked as ours, so they never read as the reader leaving. A reader's drag cancels it, as user input already outranks our scrolls. | #1's rules |

## Cost (Android/iOS later)

The text relayouts only when a whole word is added, the same order as today's 50 ms batches. Each frame then draws
one gradient mask (`BlendMode.DstIn`) over the last one or two text blocks, in an offscreen layer that exists only
while they reveal. Nothing runs when idle.

## #1's autoscroll: unchanged

The glide replaces only *how* growth of the last row is followed while the reader is at the end, and only for a
revealing reply.

These stay as they are:
- the first jump on opening a chat;
- the follow/leave decision (FOLLOW_SLOP, decided by where the transcript is scrolled);
- held growth during a reader's scroll;
- new rows (`animateScrollToItem` then pin);
- find holding the transcript;
- tool groups growing.

`ChatAutoscrollTest` must stay green unchanged.

## Testing

- **`RevealClockTest`** (shared, pure):
  - pace: τ, floor, catch-up when done;
  - the trailing half word waits, with the long-token and done exceptions;
  - `laidOut` is the word the head is in;
  - alpha: positional edge, age floor, past-head zero;
  - idle once caught up and dry;
  - reduced mode gives whole words and alpha 1.
- **`blockSourceEnds` tests:** prose with bold, headings, list items, a quote, and a code block in between.
- **`LiveRevealTest`** (Compose UI, test clock):
  - text appears progressively, not all at once;
  - `waitForIdle` ends with the full text;
  - reduced motion shows whole words only;
  - the stored row continues an unfinished reveal, and its final text is complete after idle.
- **`ChatAutoscrollTest`:**
  - all existing tests unchanged;
  - new: while following, a growing live reply glides (partway after one frame, at the end after idle);
  - new: a reader's drag during a glide stops it and is not overridden.
