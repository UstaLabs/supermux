# Agents show HTML in the chat (#104) — design

Status: **design for review. Nothing of the feature is built.** Card #104. The research and the earlier proposal
are on the card (`research.md`, `proposal.md`).

Order, set by Ahmet on 2026-11-08:
1. **The evals come first** (see **Evals**): they decide whether one tool is enough.
2. Then **#105's Browser tab**, which this session also builds. It wires webview-compose into the apps.
3. Then this card.

## Ahmet's decisions

- **One tool to start: `show_html({ title, html | file, height? })`.** The evals compare it with two alternatives (A/B/C below), and the winner is the design. Everything else in this spec holds for all three.
- **No native visual or form blocks.**
- **The tool reports render problems:** script errors, a blank page, overflow at phone width. The agent can fix the page and call again.
- **The network is open,** as in T3 Code.
- **The page is always isolated from supermux.** It has an opaque origin and is never same-origin with the app. From the app it gets theme, size, links and click-to-send, and nothing else.
- **No label or badge on cards.**
- **A click in the page sends straight away.** The message shows in the chat as his, from the card. It works only after a real click or tap in that card, on all six platforms, and tests prove it.
- **Each call makes a new card.**
- **Limits:** 2 MB per page; inline height up to ~70 % of the visible chat.
- **Full screen:**
  - desktop and tablet: #105's Browser tab;
  - phone: a view over the chat;
  - agent pages share one reusable Preview tab.
  These are the prototype's defaults for Q7, unless he changes them.

## The tool

Variant **A** is the starting point:

```
show_html({
  title: string,     // 1–80 characters, the card's title
  html?: string,     // a whole document or a fragment; exactly one of html / file
  file?: string,     // a path to an .html file the broker can read (sessions run on the broker's host)
  height?: number,   // px for the first paint when the check can't measure (80–2000, default 280)
})
```

The result is text, one of:
- `shown (id: h_7f3a): 640 px tall on a desktop, 910 px on a phone`
- `not shown — fix the page and call show_html again:`, followed by the problems
- `shown without a check (no Chrome, Edge or Chromium on this machine)`

Notes that don't stop the card follow the result line: resources that failed to load, and the first five `console.error` lines.

The other variants:
- **B:** `preview_html({ html | file })` checks the page and returns the problems plus a phone and a desktop screenshot, to the agent only. `show_html` publishes, and still checks. This is T3 Code's `html_preview` + `html_render`.
- **C:** A, plus one instruction line: "read the result; if it lists problems, fix the page and call again".

## The check

One check serves the tool's result (every variant), the card's stored heights and the evals.

- **Where:** on the broker's host, in a Chromium-family browser it finds: Chrome, Edge, Chromium or Brave. Edge is always on Windows. If there is none, the page is shown without a check and the result says so.
- **In what:** the way the web app will run it, the strictest of the six platforms. That is a sandboxed `blob:` frame with an opaque origin, inside a host page with the card's colour scheme. Storage (`localStorage`, cookies, IndexedDB) throws there, so a page that needs it fails the check rather than failing on the web only.
- **How:** as T3 Code's `apps/server/src/htmlRender/headlessChrome.ts` does.
  - `--headless=new --remote-debugging-pipe`, so no port is opened.
  - A fresh profile for each check under `~/.mux/cache/html-check/`.
  - The page is served by request interception on its own origin.
  - The theme and a stub `supermux` API are injected, as the app does.
- **Network:** open, except loopback, LAN, link-local and the broker's own hosts.
  - A local proxy refuses those (`--proxy-server`, `--proxy-bypass-list=<-loopback>`, WebRTC off).
  - This way a page can't reach services on the host while it is checked. Pages on the user's device are not limited this way.
- **Widths:** 390 × 844 (a phone) and 720 × 900 (the desktop chat column), dark theme.
- **Settling:** the check waits for load, then 1 s with no network activity or 4 s, whichever comes first. The cap is 6 s, with at most 2 checks at once.
- **Problems** (the card is not shown):
  1. **Script error:** an uncaught exception or unhandled rejection before the page settles, or a script that failed to load. Message and line.
  2. **Blank:** nothing painted at phone width. Fewer than 40 pixels of the card's screenshot differ by more than 8 levels from its main colour. That also catches text drawn in the background's own colour.
  3. **Overflow at phone width:** the document is wider than 390 px. The result names the widest element.
  4. **Endless height:** the root element's height follows the frame's: with the frame at 600 px and then at 1200 px, the root grows by 400 px or more (`vh` units, `height: 100%` on html/body). In a card it would grow without end. T3 Code rejects these too.
- **Heights:** the heights at both widths are stored with the card, measured with the frame at 80 px so they are the content's own. The app sizes the frame from them until the live page reports its own.
- The four problems stop the card by default (open decision 1).

## The page API

The page sees one small API, `window.supermux`, injected by the app over webview-compose's bridge:

- **`supermux.send(text)`** returns a promise of `{ ok: true }` or `{ ok: false, reason }`. The rules are in the next section.
- **Theme:** `--sm-*` CSS variables on `:root` (#91's palette) and `data-theme="dark" | "light"` on `<html>`. Both update live.
- **Height:** automatic. The library measures the page.
- **Links:** `<a href>` opens outside the app, on a click, and only for http(s), mailto, tel and sms (the allowlist from car 2).

Nothing else crosses the bridge: no session, no tools, no files, no app state.

## Click-to-send: only a real click or tap in that card

The rules, the same everywhere:
- `send` is accepted only within about 1 s after a real pointer, touch or key event that the user made in that card's view.
- One activation allows one send.
- `text` is 1–2000 characters.

A refused call returns `{ ok: false, reason }` to the page and is logged. It is never sent silently. These are refused:
- a call while the page loads;
- a call from a timer or a promise with no gesture;
- a call after a script-made `el.click()` or `dispatchEvent`;
- a call after the window;
- a call from a different card;
- a second send from one click.

**Web (iframe).** Both of these must hold:
- the frame has transient user activation at call time (`navigator.userActivation.isActive`, read inside the frame by the library's shim);
- the frame was the last thing the user interacted with (the host's own pointer and focus record).

Focus alone isn't enough, because a page can refocus itself; T3's `McpAppFrame.tsx` says the same. The shim is the library's and is injected at document start. The host also checks that the message came from that card's frame and nonce.

**Native (WKWebView on macOS and iOS, Android WebView, WebView2, WPE).** The host records the real pointer, touch and key events delivered to that card's view; webview-compose already tells the user's gestures apart for navigations. The library adds:
- a stamp of the view's last real input;
- consuming it, so one gesture allows one send.

The app accepts `send` within 1 s of that stamp. A page can't fake it, because the stamp lives in the host, not in the page's JavaScript.

**Built and tested for the web already,** in the eval harness:
- the shim is `evals/show-html/page-api.ts` and the host is `click.ts`;
- 5 tests in real Chrome cover each refused case and the happy path.

The shim captures `Reflect.apply` and the `userActivation` getters before any page script runs, so the page can't swap them out.

**Tests for the build:**
- On every platform, on the library's fake engine: each refused case and the happy path.
- On real engines on macOS and the web: the same cases, as a conformance check.
- The evals' harness page carries the same cases (see **Evals**).

## Isolation

1. **Every page has its own opaque origin and is never same-origin with the app.**
   - Native: `https://c<hash>.invalid` in an ephemeral profile, which holds no supermux cookie.
   - Web: a frame with only `sandbox="allow-scripts"`, loaded from a **`blob:` URL, not a srcdoc**. WebKit sends the app's `SameSite=Lax` cookie from a sandboxed srcdoc frame (measured 2026-11-08, card #104 `isolation-test/`). `blob:` and `data:` frames get it in no engine. The change is in webview-compose's web engine (`IframeWebEngine.kt:85`).
2. **The broker refuses the page.** A request that carries the cookie is refused, before routing:
   - if it has `Origin: null`;
   - if it has `Sec-Fetch-Site: cross-site`, except a top-level GET navigation;
   - if it is a WebSocket to the app's own routes from any origin but the app's.

   This ships ahead of #104 as its own security car (`crossSiteRefusal`, `src/channels/web/cookies.ts`).
3. **Re-check on real Safari** (Mac and iPhone) before ship. The GTK build we tested shares WebKit's cookie decision, but not Safari's network stack.
4. **Never a page on the broker's origin.** `/files/` serves HTML as an attachment with `CSP: sandbox`, and the web app opens active types as downloads (both on dev). The app fetches the page as bytes.
5. **The bridge carries only the page API above.** Every `message` listener in the web app gets audited: ChatGPT's October 2026 takeover came through a parent listener that accepted any origin.
6. **Claude Code's own `Artifact` tool stays off** (it is off unless `CLAUDE_CODE_ARTIFACT=1`).

## Rendering

- **Desktop and tablet:**
  - The card sits inline in the reply. It shows the title, full screen and ⋯ (Open in a Browser tab, View source, Save as .html, Copy source).
  - The height comes from the stored heights, capped at ~70 % of the visible chat; past that the page scrolls inside.
  - The page is live once on screen (the library's activation) and a still otherwise.
  - Full screen opens `openInBrowserTab(workspaceId, Doc(id))` (#105). The tab renders through the same `AgentHtmlView(ref, mode = Tab)`, and the card says where the page is ("Live in the Preview tab · Show here").
- **Phone:**
  - The same inline card. On iOS a drag scrolls the chat and a tap reaches the page; on Android a drag that starts on the page belongs to it.
  - Full screen is a view over the chat; back returns to the same scroll.
- **Web:** the same card in the `blob:` frame. The app's document must declare the card's colour scheme. A frame whose `color-scheme` differs from its embedder's gets an opaque canvas from the browser, which breaks the card's transparency.
- **Old messages:** the same card, fetched lazily. After a restart a card shows a still until it goes live.
- **Telegram and WhatsApp** (maintenance only): the `.html` is sent as a document, captioned with its title.
- **Per-card state:** kept while the app runs, through the library's page memory. It is not synced.

## Storage and the wire

- **Storage:**
  - The broker stores the page in the FileStore as `text/html` with kind `html`, named `<title>.html`.
  - It belongs to the session and is ref-counted like `attach`'s files (`src/core/routing/transform-outbound.ts`).
  - It posts an agent message with one attachment: `{ file_id, kind: "html", mime: "text/html", name, size, title, heights }`.
- **Old apps** draw it as a file, which downloads, because `/files/` sends HTML as an attachment.
- **The tool:** `show_html` is an outbound tool next to `attach` in `src/shim/tools.ts`, and `toolResult` maps its result text. Running sessions see it after their next restart.
- **Click-to-send:**
  - The app's `send` frame gains `args.from_card: { file_id }`.
  - The broker checks that the file is an html attachment of a message in that session, and stores `from_card: { file_id, title }` on the user's message.
  - The chat shows his bubble with a small "from ‹title›" line. The agent receives the text after the line `(from the card "‹title›")`.

## Agent instructions

The tool's description, plus a short **Interactive HTML** section next to **Diagrams** in `prompts/environment.md`. The evals tune the wording (two versions are compared). The starting text:
- **When:** a visual or interactive result explains better than text, or the user asks for a chart, a comparison, a mockup or a small tool. Also proactively, when they paste numbers and ask a question a chart answers.
- **When not:** a factual answer, "just tell me", code work that only mentions charts, or an image to post (attach a PNG).
- **How:**
  - one self-contained document;
  - the `--sm-*` theme variables, with no page background, card or outer padding;
  - fluid widths;
  - charts at fixed pixel heights;
  - no `vh`;
  - no storage (`localStorage` and cookies throw in the card);
  - a choice goes back with `supermux.send()`.
- **After the call:** read the result; if it lists problems, fix the page and call again.

## Evals (first, before any build)

Ahmet: *"maybe a single tool isn't enough."* The evals decide between A, B and C and tune the instructions. Nothing else is built until he has seen the results.

**The harness** (`evals/show-html/` in the repo; no app UI, no broker changes):
- **A stub MCP tool server** serves the candidate tools of the variant under test with their candidate descriptions.
  - It records every call: arguments, timing, size.
  - It runs **the check above** (the same code that will ship), so the problem report the agent reads is the real one.
  - For B's `preview_html`, it returns the two screenshots.
- **A harness page** loads each shown page the way the app will: the theme, the `supermux` stub, a `blob:` frame. It drives the click round trip:
  - a real click through the DevTools protocol, then the `send` it produces;
  - the refused cases from **Click-to-send** (on load, a timer, a script `click()`, a second send).
- **Runner:** each agent CLI, non-interactive, in a throwaway folder, with only the stub server attached. Each gets the variant's instruction text where supermux would put it (the environment prompt):
  - `claude -p --strict-mcp-config`;
  - `codex exec`;
  - `opencode run`;
  - `grok -p`;
  - `agy -p`;
  - `cursor-agent -p`.

  One prompt per run. A problem report is answered by the agent itself, which measures self-repair.
- **Isolation:** the runs never touch the live broker or his sessions. Every folder is deleted after its run, and the check's proxy refuses local addresses.

**The prompts** (18, fixed):

| # | Prompt | Expected |
|---|---|---|
| 1 | "Chart these numbers" + a small table | a card |
| 2 | "Compare these 3 options" (prices and features) | a card |
| 3 | "Make a small calculator for X" | a card, interactive |
| 4 | "Show a UI mockup of a settings screen" | a card |
| 5 | "Let me pick a color palette" | a card with `supermux.send` |
| 6 | Latency per release pasted, "which release regressed?" | a card (proactive) |
| 7 | CI job times pasted, "what eats the time?" | a card (proactive) |
| 8 | "Tune the retry backoff, I'll pick the values" | a card with sliders + send |
| 9 | "A timeline of these events" | a card |
| 10 | "Explain this regex visually" | a card |
| 11 | "What's the capital of Australia?" | no card |
| 12 | "Just tell me, in one line, …" | no card |
| 13 | "Refactor UsageChart.tsx so the hover logic is a hook" | no card (the word "chart") |
| 14 | "Make an image I can post on X" | no card: a PNG through `attach` |
| 15 | "Fix the failing test" | no card |
| 16 | Prompt 1, but the stub answers the first call with "blank" | a fixed re-call |
| 17 | Prompt 2, but the stub answers the first call with an overflow at 390 px | a fixed re-call |
| 18 | Prompt 3, but the stub answers the first call with a script error | a fixed re-call |

Prompts 16–18 measure self-repair reliably: the stub forces one realistic problem report on the first call, whatever the page was. The re-call must change the page and pass the real check. Real problems on the other prompts count too.

**Models:** each agent kind and a few models per kind, cheap first:
- Claude: Haiku, Sonnet, Opus.
- Codex: GPT-6 Luna, GPT-5.6 Terra.
- Grok: Grok 4.7.
- OpenCode: a free model and a paid one.
- Antigravity: Gemini 3.8 Flash and Gemini 4 Argon.
- Cursor: if quota allows.

**Arms:** variants A, B and C, each with two instruction wordings, so six arms.

**Phases** (six arms × 18 prompts × 12 models × 3 samples would be ~3,900 runs, so the runs are phased):
1. **Smoke:** Haiku, every arm, 1 sample (108 runs). This proves the harness and the scoring.
2. **Breadth, cheap models:** Haiku, Luna, Flash and the free OpenCode model, every arm, 1 sample. A second sample runs where an arm's result on a prompt is borderline.
3. **Depth, after Ahmet sees the estimate:** the best two arms on Sonnet, Opus, Terra, Argon, Grok, a paid OpenCode model and Cursor, with 2 samples.

**Scores per model × arm:**
- **Tool use:** a card when expected, none when not (precision and recall, the negatives counted apart).
- **Page quality:** self-contained (no local paths), valid, uses the theme variables, no page background.
- **Check result:** renders with no script error, isn't blank, fits at 390 px, no endless height.
- **Self-repair:** after a problem report, it fixed the page and called again (and how many tries it took).
- **Click round trip:** where the prompt asks for a choice, the page sends on the real click and is refused on every forged one.
- **Cost:** tokens and money per run, and turns.

**Output:**
- a table per model × arm;
- the instruction wording that works best across them all;
- a recommendation (A, B or C);
- the cost.

**Cost control:**
- The CLIs run on his own accounts. Claude, Codex, Antigravity and Cursor runs spend the **same subscription limits the fleet works on**, and OpenCode's paid models cost money.
- Every run records its tokens and money, priced at each provider's API rates, and the runner tracks the share of each account's window it uses.
- Phase 3 starts only after Ahmet has seen the estimate built from phases 1–2's measured tokens.
- At most 3 runs at once, and none while the box's load is above ~12. Each run is capped at 4 minutes and 8 turns.

## Build, after the evals and #105

TDD, cars to Muxie, real checks on his Mac, Fold and iPhone.

1. **The check** as a broker module: browser discovery, the pipe, the proxy, the four problems. Tests on fixed pages, one per problem, plus a clean one.
2. **The tool:** `show_html` (or the evals' winner) in `src/shim/tools.ts`, storage, the message, `toolResult`, and the `from_card` user message.
3. **webview-compose:**
   - the web engine loads `Html` from `blob:`;
   - the input stamp, consumed by one send, on every engine;
   - the click-to-send tests above.
4. **The app:**
   - `AgentHtmlView(ref, mode)`, the card in `Timeline.kt`'s media slot;
   - full screen through #105's `openInBrowserTab`;
   - the phone view;
   - the "from ‹title›" line.
5. **The agent instructions,** as the evals tuned them.
6. **The real-Safari isolation check,** and the six platforms by hand.

## Not in v1

- Updating a card in place.
- `url` (links belong to #105).
- Rendering the agents' own formats (Codex's `::codex-inline-vis`, Antigravity's, OpenCode's).
- Tools callable from the page (MCP Apps).
- Inlining local images that a `file` page references. The check lists them as failed loads.
- State synced across devices.

## Open decisions (defaults in bold)

1. A page with a problem: **not shown, the agent re-calls** · or shown with a warning.
2. Keyboard on the web, while a page has focus: **the page becomes unfocusable while you type in the app** · or accept the residual (webview's pending choice).
3. The check without a browser on the host: **shown, said in the result** · or offer to download a headless Chromium (~150 MB) once.
