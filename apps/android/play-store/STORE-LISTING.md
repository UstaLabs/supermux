# supermux — Google Play store listing copy

Everything here is ready to paste into Play Console → *Grow → Store presence → Main store listing*.
Character limits are Google's; counts are noted so you can edit safely.

---

## App name  (max 30 chars)
```
supermux
```
Keyword-richer alternatives (each ≤30, pick one if you want search terms in the name):
- `supermux: coding agents 24/7`  (28)
- `supermux — run coding agents`  (28)

## Short description  (max 80 chars)
```
Run Claude Code, Codex & Cursor 24/7 on a box you own — from your phone.
```
(71 chars. Fuller variant, 80 exactly: `Run Claude Code, Codex, Cursor & OpenCode 24/7 on your own box, from a phone`.)

## Full description  (max 4000 chars — this draft ≈1500)
```
supermux gives your AI coding agents a home of their own — a box you own (a VPS, a mini PC, the spare laptop in a drawer) where Claude Code, Codex, Cursor and OpenCode run around the clock. This app is your window into all of them, from your phone.

Coding agents only make progress while you're watching. Close the laptop and the session dies; a finished diff waits hours for review. supermux keeps every session alive on your box and brings them to your pocket.

WHAT YOU CAN DO
• A chat per session — spawn an agent from your phone, answer a worker's question from the train. Voice input with a live waveform, photos and camera included.
• Code review, pocket-sized — read the full diff grouped by repo, drop inline comments, the agent addresses them, and your main branch moves only on your word.
• A full workspace — file tree, an editor with search, and a real terminal in the session's directory.
• Push that finds you — a notification when a worker finishes or asks; one tap opens that exact session.

FOUR AGENTS, ONE INTERFACE
Claude Code, Codex, Cursor and OpenCode drive identically — same chat, same workspace. Switch models live mid-session, dial thinking effort up for the hard problems, and mix agents freely across sessions.

YOURS, END TO END
No vendor cloud. No account. supermux talks only to the broker you run on your own hardware — your agent subscriptions, your code, your box. Reach it over your LAN, a reverse proxy, a tunnel or a VPN.

REQUIRES YOUR OWN SERVER
This is the mobile client. You first install the free, open-source supermux broker on a machine you control (macOS, Linux or Docker) — one command from supermux.dev. Without a broker to pair with, the app has nothing to connect to.

Open source (MIT). Built with supermux.
```

> ⚠️ The **"REQUIRES YOUR OWN SERVER"** paragraph is deliberate — it tells Google's
> reviewer (and users) the app needs a backend they set up, so it isn't judged as a
> broken standalone app. Keep it.

## Release notes  (max 500 chars per language — "What's new")
```
First release of the native Android client.
• One chat per agent session — voice, photos, camera
• Full code review with inline comments
• File browser, editor and a real terminal
• QR + deep-link device pairing
• Push when an agent finishes or needs you
Requires a supermux broker on your own machine — supermux.dev
```

---

## Categorisation & contact (Play Console → store settings)
| Field | Value |
|---|---|
| App or game | **App** |
| Category | **Productivity** (alt: Tools) |
| Tags | choose ≤5 from Google's list: *Developer tools, Productivity, Utilities* |
| Email | `support@supermux.dev` |
| Website | `https://supermux.dev` |
| Phone | optional |
| Privacy policy URL | host `PRIVACY-POLICY.md` → see RELEASE-CHECKLIST.md |

## Graphics needed (sizes Google enforces)
| Asset | Spec | Status |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | ✅ ready — `play-store/assets/icon-512.png` |
| Feature graphic | 1024×500 PNG/JPG | ✅ generated — `play-store/assets/feature-graphic.png` |
| Phone screenshots | 2–8, 16:9 or 9:16, ≥320px | ⏳ captured from emulator |
| 7" / 10" tablet shots | optional, recommended | ⏳ optional |
