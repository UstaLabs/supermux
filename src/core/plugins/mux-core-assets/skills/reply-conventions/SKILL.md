---
name: reply-conventions
description: Use whenever you need to send anything to the user from a supermux session — establishes that all user-facing words go through the supermux reply tool, and the per-channel rules (Telegram vs web).
---

# Replying as a supermux session

You are running inside **supermux**: a broker connecting the user's chat
client(s) to many agent sessions on this machine. You are ONE session. The
broker routes the user's inbound messages to you and relays your replies back to
their chat client — your terminal/transcript output never reaches them.

## How replies reach the user (differs per agent)

- **Claude Code sessions:** every user-facing word MUST go through the
  **`mcp__mux-shim__reply`** tool. Text written only to your transcript or
  tmux pane is invisible.
- **Cursor and Codex sessions:** you do **NOT** call a reply tool. Your normal
  assistant response **is** the message — the broker captures it and relays it
  to the user. Just respond normally.

Either way the rule below is the same: nothing reaches the user unless it goes
out as a proper reply (the tool for Claude, your normal response for
Cursor/Codex). Terminal-only output never reaches them.

## The one rule that matters

**Everything the user should see must leave the session as a reply** — via the
`mcp__mux-shim__reply` tool (Claude) or your normal assistant turn
(Cursor/Codex). The user reads it on their phone or web client, not in your
terminal.

- `reply(chat_id, text, …)` — send a message. Push-notifies. Your primary output.
- Each inbound message carries `chat_id` and `message_id` in its meta. Pass that
  same `chat_id` back when replying.
- Don't reply to empty inbounds. Genuine internal scratch work needs no reply.
- Long replies (200+ words) are fine. Don't dump whole files; summarize and
  offer to send the full content on request.

## Which channel am I on? (the `chat_id` prefix tells you)

- `telegram:…` — **Telegram.** Reactions, message edits, attachments, and voice
  are supported. You may format with `markdownv2`. One Telegram chat is
  multiplexed across sessions — exactly one session is "active" at a time.
- `web:…` — **Web PWA.** Reactions and edits are NOT supported — do not call
  `react` / `edit_message`. Attachments ARE supported. Markdown renders in the
  app, so do NOT use `markdownv2`. Every session is its own separate chat.

## Side-effect tools (mux-shim)

- `react` / `edit_message` — message side-effects (Telegram only).
- `download_attachment` — fetch a file the user sent; returns a local path.

(Orchestration tools like `spawn_session` / `set_active` / `expose_port` are
described in your environment instructions.)
