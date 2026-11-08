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
  file?: string,     // a path to an .html file inside the session's working folder
  height?: number,   // px for the first paint when the check can't measure (80–2000, default 280)
})
```

The result is text, one of:
- `shown (id: h_7f3a): 640 px tall on a desktop, 910 px on a phone`
- `not shown — fix the page and call show_html again:`, followed by the problems
- `shown without a check (no Chrome, Edge or Chromium on this machine)`

Notes that don't stop the card follow the result line:
- resources that failed to load;
- local addresses the check refused;
- the first five `console.error` lines;
- a height that follows the frame (see the check);
- `supermux.send` calls made with no click.

- **`file` stays in the session's folder.** It must resolve, after symlinks, inside the session's working folder and never under `~/.mux`. Otherwise the broker would read files the agent itself may not read into a card with an open network.
- **The tool carries `readOnlyHint: true`.** It changes no file, so plan modes (Claude's among them) don't refuse it.

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
- **Network:** open, except loopback, LAN, link-local, CGNAT/Tailscale, NAT64/6to4 and the broker's own hosts.
  - A local SOCKS5 proxy, one per check, enforces it at the socket, with `<-loopback>`, so loopback goes through it too.
  - The proxy resolves the name itself and refuses it if any of its addresses is private (`node:net`'s `BlockList`). So `127.0.0.1.nip.io`, a bare LAN hostname, an IPv6 literal in any spelling (`[::1]`, `[::ffff:127.0.0.1]`) or a rebinding name can't get through.
  - The ranges: 0/8, 10/8, CGNAT 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.168/16, 198.18/15, multicast and 240/4; IPv6 `::`, `::1`, NAT64, Teredo, 6to4, ULA, link-local, multicast; IPv4-mapped addresses by their IPv4 rule.
  - It takes CONNECT only, dials IPv4 first, and holds client bytes until its reply is sent.
  - WebRTC is limited to proxied UDP, which is none.
  - This way a page can't reach services on the host while it is checked. Pages on the user's device are not limited this way.
- **Widths:** 390 × 844 (a phone) and 720 × 900 (the desktop chat column), dark theme.
- **Settling:** the check waits for load, then 1 s with no network activity or 4 s, whichever comes first, then for fonts and two frames.
  - Reduced motion is on, so animated charts are drawn when measured.
  - The cap is 6 s, with at most 2 checks at once.
  - A page that hasn't rendered by the cap is a script error ("didn't finish rendering within 6 s"), never shown unchecked. Only "no browser on this machine" shows a page unchecked.
- **Problems** (the card is not shown), Ahmet's three:
  1. **Script error:** an uncaught exception or unhandled rejection before the page settles, or a script that failed to load. Message and line.
  2. **Blank:** nothing painted at phone width. Fewer than 40 pixels of the card's screenshot differ by more than 8 levels from its main colour. That also catches text drawn in the background's own colour.
  3. **Overflow at phone width:** the document is wider than 390 px. The result names the widest element.
- **A note, not a problem:** a page whose height follows its frame (`vh` units, `height: 100%`, a `100vh` child). With the frame at 600 px and then at 1200 px, its lowest element moves down by 400 px or more. The card's ~70 % cap and webview-compose's own loop detector already stop it from growing, so it doesn't block the card.
- **Heights:** the heights at both widths are stored with the card, measured with the frame at 80 px so they are the content's own. The app sizes the frame from them until the live page reports its own.
- The three problems stop the card by default (open decision 1).

## The page API

The page sees one small API, `window.supermux`, injected by the app over webview-compose's bridge:

- **`supermux.send(text)`** returns a promise of `{ ok: true }` or `{ ok: false, reason }`. The rules are in the next section.
- **Theme:** `--sm-*` CSS variables on `:root` (#91's palette) and `data-theme="dark" | "light"` on `<html>`. Both update live.
- **Height:** automatic. The library measures the page.
- **Links:** `<a href>` opens outside the app, on a click, and only for http(s), mailto, tel and sms (the allowlist from car 2).

Nothing else crosses the bridge: no session, no tools, no files, no app state.

## Click-to-send: only a real click or tap in that card

The rules, the same everywhere:
- A send is armed only by a click that the engine itself made on a **button** in that card: `button` or `input[type=button|submit]`. `[role=button]` isn't enough, because a page can put it on the whole body.
  - That covers a tap, a mouse click, Enter or Space on a focused button, and a screen reader's activation.
  - It doesn't cover a scroll, a drag, a key press elsewhere, or a page's own `el.click()` or `dispatchEvent`.
- The send must come within 1 s of that click, and one click allows one send.
- `text` is 1–2000 characters.
- The send travels on **the card's private channel**. The shim creates it at document start, before any page script runs, and sends one end up with this document's nonce. A message the page posts on its own is never a send.
- The host accepts one channel per document it built. A document the page navigates to on its own has no shim and doesn't know the nonce.
- The host re-checks every send:
  - the click is within the last second, by its own clock;
  - the user hasn't touched the app since;
  - the browser reports the user's activation at the host too, since activation propagates from the card to its embedder.

A refused call returns `{ ok: false, reason }` to the page and is logged. It is never sent silently. These are refused:
- a call while the page loads;
- a call from a timer or a promise with no click;
- a call after a script-made `el.click()` or `dispatchEvent`;
- a click that isn't on a button, or a key press;
- a call after the 1 s window;
- a call from a different card;
- a second send from one click;
- a send after the user touched the app;
- a message the page posts straight to the app.

Focus is never trusted: a page can move focus itself, and a window that wasn't focused gets no blur on a real click.

**Web (iframe).** The shim is part of the document the app builds, so it runs first. It does four things:
- It captures every accessor it touches later: `Reflect.apply`, the `data`, `target` and `nodeType` getters, `Element.prototype.closest`, `MessagePort.prototype.postMessage` and the `userActivation` getters. A page can redefine prototype accessors, but it can't reach these captured references, so it can neither steal the channel nor fake a click's target.
- It makes its `MessageChannel` and posts one end to the host at once.
- It listens for trusted `click` events in the capture phase.
- It removes its own script element, so the nonce isn't in the DOM.

The frame's transient user activation (`navigator.userActivation.isActive`) must hold at call time.

**Native** (WKWebView on macOS and iOS, Android WebView, WebView2, WPE):
- The same shim runs through the library's document-start bridge. On Apple's engines it runs in its own `WKContentWorld`, out of the page's reach.
- The host also records the real pointer, touch and key input delivered to that card's view. A send is accepted only within 1 s of that input, consumed by the send. On Windows this works in webview-compose's visual hosting, where the library forwards the input itself.
- Open decision 4: assistive input (VoiceOver, TalkBack, Voice Control, Switch Access, Narrator) reaches the page as a click but delivers no input to the view.

**Built and tested for the web already,** in the eval harness:
- the shim is `evals/show-html/page-api.ts` and the host is `click.ts`;
- 8 tests in real Chrome cover every refused case above and the happy path;
- they include a page that patches every prototype accessor to steal the channel, fakes a click's target, and sends a hello of its own.

**Tests for the build:**
- On every platform, on the library's fake engine: each refused case and the happy path.
- On real engines on macOS and the web: the same cases, as a conformance check.
- The evals' harness page carries the same cases (see **Evals**).

## Isolation

1. **Every page has its own opaque origin and is never same-origin with the app.**
   - Native: `https://c<hash>.invalid` in an ephemeral profile, which holds no supermux cookie.
   - Web: a frame with only `sandbox="allow-scripts"`, loaded from a **`blob:` URL, not a srcdoc**.
     - WebKit sends the app's `SameSite=Lax` cookie from a sandboxed srcdoc frame (measured 2026-11-08, card #104 `isolation-test/`). `blob:` and `data:` frames get it in no engine.
     - The change is in webview-compose's web engine (`IframeWebEngine.kt:85`).
     - With it, the shim's link handling needs its own base: a `blob:` document has no useful `document.baseURI`, so `new URL(raw, baseURI)` throws on relative hrefs.
     - The engine also revokes each blob URL when its document goes.
2. **The broker refuses the page.** A request that carries the cookie is refused, before routing:
   - if it has `Origin: null`;
   - if it has `Sec-Fetch-Site: cross-site`, except a top-level GET navigation;
   - if it is a WebSocket to the app's own routes from any origin but the app's.

   This ships ahead of #104 as its own security car (`crossSiteRefusal`, `src/channels/web/cookies.ts`).

   A proposed follow-up: refuse a cookie request to the app's own routes unless `Sec-Fetch-Site` is `same-origin` or `none`. That closes the same-site case, a proxied page on `<slug>.<base>` loading the app's GET routes with the `Domain` cookie. Responses can't be read there today, but GETs with side effects still run.
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

**The prompts** (24, fixed; `prompts.ts` has the full text):

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
| 9 | "A timeline of these events" | a card, or a mermaid diagram |
| 10 | "Explain this regex visually" | a card, or a mermaid diagram |
| 11 | "What's the capital of Australia?" | no card |
| 12 | "Just tell me, in one line, …" | no card |
| 13 | "Refactor UsageChart.tsx so the hover logic is a hook" | no card (the word "chart") |
| 14 | "Make an image I can post on X" | no card: a PNG through `attach` |
| 15 | "Fix the failing test" | no card |
| 16 | Prompt 1, but the stub answers the first call with "blank" | a fixed re-call |
| 17 | Prompt 2, but the stub answers the first call with an overflow at 390 px | a fixed re-call |
| 18 | Prompt 3, but the stub answers the first call with a script error | a fixed re-call |

| 19 | A dark card from "earlier in this chat", then "make it light mode" | a card |
| 20 | 120 days of daily active users, "what does the trend look like?" | a card |
| 21 | A small table, "just give me the number" | no card |
| 22 | Q3 sales by region, "anything stand out?" (held out) | a card |
| 23 | "Sketch the layout of a kanban board" (held out) | a card |
| 24 | "Rename cnt to count in counter.js" (held out) | no card |

- **Prompts 16–18 measure self-repair reliably.**
  - The stub forces one realistic problem report on the first `show_html`, whatever the page was. It never forces one on `preview_html`, whose screenshots would contradict it.
  - The repair must be a different page (its hash is recorded), and it must pass the real check.
  - Real problems on the other prompts count too.
- **Held out:** prompts 22–24 were written after the wordings, in phrasing neither echoes, and are reported apart.
- **Both wordings carry the same content:** when, when not, how, sending, the result. Wording 1 says it as terse rules; wording 2 adds reasons and an example. Both sit after supermux's own **Diagrams** section, so a mermaid answer competes as it does in the app.

**Models:** each agent kind and a few models per kind, cheap first:
- Claude: Haiku, Sonnet, Opus.
- Codex: GPT-6 Luna, GPT-5.6 Terra.
- Grok: Grok 4.7.
- OpenCode: a free model and a paid one.
- Antigravity: Gemini 3.8 Flash and Gemini 4 Argon.
- Cursor: if quota allows.

**Arms:** variants A, B and C, each with two instruction wordings, so six arms.

**Phases** (six arms × 24 prompts × 12 models × 3 samples would be ~5,200 runs, so the runs are phased):
1. **Smoke:** Haiku, A1, B1 and C1 over the 24 prompts, 1 sample (72 runs). This proves the harness and the scoring.
2. **The smallest trustworthy run** (council-19's estimate), on the two CLIs that can run now, Haiku and a free OpenCode model, with 3 samples:
   - Stage 1: A1 vs B1 vs C1, 432 runs (144 paired trials per arm). That's enough to see a 10–12 point difference in tool use, or a 25-point difference in repair, at p < 0.05 (McNemar).
   - Stage 2: the winner's two wordings, 144 more runs.
   - In all, 576 runs, 288 of them on Haiku.
3. **Depth, after Ahmet sees the estimate:** the winning arm on Sonnet, Opus, and whichever of Codex, Antigravity, Grok and Cursor can run by then.

**Scores per model × arm:**
- **Tool use:** a card when expected, none when not (precision and recall, the negatives counted apart).
  - A `preview_html` on a no-card prompt doesn't count against it, because the user sees nothing.
  - B's habit of previewing first is reported apart.
- **Page quality:** self-contained (no local paths or `url()`s), uses the theme variables, no page background. The background is measured by the check, not read from the CSS.
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

1. **The check** as a broker module: browser discovery, the pipe, the proxy, the three problems. Tests on fixed pages, one per problem, plus a clean one.
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
4. **Screen readers and click-to-send on native platforms.**
   - VoiceOver and TalkBack activate a button as a click inside the page, but deliver no input to the view. So the host's input stamp (your rule for native platforms) refuses their sends.
   - **(a) accept the shim's trusted button click alone while assistive input is on.** The app asks the OS (`UIAccessibility.isVoiceOverRunning`, `isSwitchControlRunning`, Android's `AccessibilityManager.isTouchExplorationEnabled`, Windows' screen-reader flag). That leaves gaps: Voice Control and Android's Voice Access have no reliable flag.
   - (b) keep the host stamp strict: no click-to-send with assistive input.
   - (c) council-19's view: drop the native host stamp and rely, as the web does, on the shim's trusted button click on its private channel, with the engine's own transient activation. On Apple's engines the shim runs in its own `WKContentWorld`. That works for every assistive input, but it replaces the native rule as you set it, so it's your call.

## Eval runs: what works on this box (2026-11-08)

- **Claude Code:** works. Haiku only, on account `claude-97aee119` (Tally: no Sonnet or Opus until the working sessions have room).
- **OpenCode:** works. A free model (`opencode/deepseek-v4.1-flash-free`) answered both probes.
- **Codex:** blocked. The box's only Codex login is at its usage limit until 17 Nov; skipped until Ahmet decides on an account.
- **Antigravity:** blocked. Its MCP servers live only in the user-level `mcp_config.json`; there is no per-folder one. An eval server there would also show up in his other Antigravity sessions.
- **Grok:** blocked. In print mode it doesn't load the project-scoped server in `./.grok/config.toml`. It then said "the charting tool is unavailable" and read the folder until the time cap.
- **Cursor:** limited. The free plan allows only Grok 4.7, Grok 4.6 and Auto.
