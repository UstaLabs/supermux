# Native editor M3b: touch selection, menu, zoom, accessibility, and the device pass. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `:editor-compose` usable on phones and tablets, and accessible, then prove it on Ahmet's real devices.
The devices are the iPhone 15 Pro, the iPad Air M2 and the Galaxy Fold, plus the Mac desktop app and Chrome.
M3b covers:
- touch selection handles and a copy/cut/paste/select-all menu
- pinch and `Mod +/−/0` font zoom
- VoiceOver/TalkBack semantics
- `:editor-sample` apps for iOS and Android
- a real-device input pass that Ahmet runs

**Context:**
- M3a is done: `docs/superpowers/plans/2026-09-26-native-editor-m3a-surface.md` and `apps/editor-compose/README.md`.
- Useful APIs from M3a:
  - `EditorView.typeText`, `paste`, `focus(showKeyboard)`, `coordsAtPos`
  - `DefaultCommands.copy/cut/paste`
  - `EditorScrollState`
- M3a's gaps that land here:
  - The web hardware-keyboard detection is a heuristic, so it needs an iOS Safari check.
  - The first composition character is labelled `input`, not `input.ime`.
  - Home/End work on the logical line in wrap mode.
- Terminal lessons to reuse: `apps/terminal-compose` has touch selection, clipboard, accessibility semantics and its
  device-pass notes. `~/.mux/domains/terminal.digest.md` records the three device-only bugs from 2026-09-24. Avoid
  repeating them.

**Not pre-verified.** TDD, with the interfaces as the contract. The device pass is manual and Ahmet runs it.

## Ground rules
- **No builds on the Linux host.** Use `scripts/editor/mac-sync.sh`, then `ssh mac "$MACENV; …"`. Heavy jobs run one
  at a time.
- **iOS device installs.** Follow `mux:ios-device-on-remote-mac`. The working script is
  `docs/superpowers/notes/m0-artifacts/iosProbe/probe-device.sh`:
  - The keychain password is in `mac:~/.smux-dist-kc-pass`. Never commit it.
  - Use the ASC API key for provisioning.
  - Add `CADisableMinimumFrameDurationOnPhone` to the Info.plist.
  - Give it an explicit theme and surface, or the screen renders black.
- **Android devices** use wireless adb (`mux:wireless-adb-over-vpn`):
  - The Fold was unreachable in M0 because wireless debugging was off. **Ask Ahmet to turn it on first** through the
    reply tool.
  - Install only `dev.supermux.editor.sample*` packages.
  - ⛔ Never uninstall or clear `dev.supermux.*` app packages.
- Commits end with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Touch selection handles
- **Handles:**
  - A long-press on a word selects it and shows **two drag handles** (teardrops at the selection ends, in the
    theme's accent colour).
  - A tap inside the selection keeps it and shows the menu (Task 2). A tap outside collapses it.
  - Dragging a handle moves that end. It snaps to grapheme boundaries and auto-scrolls at the edges.
  - A single cursor gets one handle after a tap, for moving the caret.
- **Magnifier:**
  - On Android, use `Modifier.magnifier` where the Compose version supports it.
  - On iOS, a simple loupe is optional. If it's cut, document that.
- **Tests** (desktop UI harness with synthetic touch):
  - Long-press selects a word.
  - A handle drag extends or shrinks the selection.
  - The handle hit targets are at least 48 dp / 44 pt.
  - The handles follow the selection through scroll and through edits.
- [ ] Commit: `feat(editor-compose): touch selection handles`.

### Task 2: The selection menu and clipboard on touch
- **The menu:** a floating menu near the selection with Cut, Copy, Paste and Select All, as a Compose popup.
  - It uses `DefaultCommands`.
  - Paste is shown only when the clipboard has text.
  - It hides while scrolling or dragging, and reappears after.
  - It is hidden when the view is `readOnly`, except for Copy and Select All.
- **Platform menus where possible:** on Android, use `TextToolbar` / `LocalTextToolbar`; on iOS, use the platform edit
  menu if Compose exposes it. Otherwise draw our own menu, styled like the platform's, and document the choice.
- **Tests:**
  - The menu appears after a long-press selection and after a tap on the caret handle.
  - Each item runs the right command, with a fake clipboard.
- [ ] Commit: `feat(editor-compose): selection menu and touch clipboard`.

### Task 3: Font zoom
- `Mod +`, `Mod −` and `Mod 0` zoom (10–24 px, default 13), and so does a two-finger pinch on touch.
- Zoom changes the font size in `EditorTheme`. The **visible top line stays put** while zooming (anchor to it).
- Report changes with an `onFontSize(px)` callback so the host can persist them per app, like today's editor.
- **Tests:** the keys step one size at a time and are clamped to 10–24; pinch scales; the anchor line stays at the
  same y within 1 line.
- [ ] Commit: `feat(editor-compose): font zoom by keys and pinch`.

### Task 4: Accessibility
- Semantics:
  - The editor exposes an editable text node, containing **the visible lines' text plus the current line**. Never
    expose the whole document.
  - The cursor position and selection are exposed, and a caret move announces its line.
  - Line numbers are hidden from screen readers.
  - The `label` parameter becomes the content description.
- VoiceOver and TalkBack must be able to read the current line, move by character and word, and type.
- **Tests:** semantic-tree assertions in the UI harness; a caret move updates the exposed selection. Real screen-reader
  checks happen in the device pass.
- [ ] Commit: `feat(editor-compose): accessibility semantics`.

### Task 5: The M3a follow-ups
- **Wrap mode:** Home/End (and `Mod-Left/Right` on Mac) go to the visual row's start or end first, then to the logical
  line's.
- **IME labelling:** label the first composition character `input.ime`. Where Compose doesn't expose composition in
  time, retro-label it: treat the next composition update as merging with the previous transaction via a history
  hint annotation.
- **Web keyboard detection:** make it overridable, and log which path each key took (debug only), so the device pass
  can verify it.
- [ ] Commit: `fix(editor-compose): visual-row Home/End, IME labelling, web keyboard detection`.

### Task 6: The sample on iOS and Android
- **Android app:** add an application target to `:editor-sample` (as `:terminal-sample` does) with its own id,
  `dev.supermux.editor.sample`.
- **iOS host app:** an Xcode host app generated by `apps/editor-sample/iosApp/project.yml`, like the M0 probe.
  - It embeds the sample's framework.
  - Set `SUPERMUX_EDITOR_SYNTAX_TABLES=YES` for this app only, so its non-core grammars have their tables. See the M2b
    notes in `apps/editor-syntax/native/README.md`.
- **Both apps open:**
  - the same Kotlin file as the desktop
  - a Markdown file with fences
  - a Turkish/emoji test file
  - a settings sheet with wrap, theme, font size, read-only, and a "debug input log" overlay that shows each
    transaction's userEvent and the key path taken
- **Build them:**
  - Android: build the APK on the Mac and copy it to Linux.
  - iOS: install it on the iPhone (`7CFA6CA8-E662-5C77-8AFE-BE12DCF52A16`) and the iPad. The iPad's id is in
    `~/.mux/domains/infra.md`.
- [ ] Commit: `feat(editor-sample): iOS and Android sample apps`.

### Task 7: The device pass (manual, Ahmet)
- [ ] Install the sample on the iPhone, the iPad and the Fold, open the desktop sample on the Mac, and serve the web
  sample. The controller exposes it through a supermux link.
- [ ] Send Ahmet this checklist through the reply tool. It's the M0 probe's 8 items, now against the real editor, plus
  editor items. Record each result.
  - **Soft-keyboard input**, on the iPhone and the Fold, and in iOS Safari (the web sample):
    1. Autocorrect `teh ` → `the `.
    2. Turkish `ğüşıöç`.
    3. Hold Backspace across a line start.
    4. Backspace at the document start.
    5. Return.
    6. Dictation, including openwhisper if it's installed.
    7. Japanese kana → kanji.
    8. A suggestion-bar tap.
    9. Gboard composing a word on the Fold with **2 cursors** (from Alt-drag on the Mac sample), and on Android via
       the debug menu's "add cursor below".
  - **Touch:**
    - Long-press to select, drag both handles, and the menu's Cut/Copy/Paste between the editor and another app.
    - Pinch zoom.
    - A fling scroll through the 10k-line file.
    - A tap raises the keyboard. Scrolling does not.
  - **Hardware keyboards:** the iPad with its keyboard and the Fold with a Bluetooth one. Arrows, word moves, Home/End,
    Cmd/Ctrl-C/X/V, and zoom keys.
  - **Screen readers:** VoiceOver on the iPhone and TalkBack on the Fold read the current line, and moving the caret
    announces it.
  - **Web:** Chrome on the Mac, and Safari on the iPhone (the soft keyboard must use the field, not the hardware-key
    path; check the debug log).
- [ ] Fix every failure (TDD where testable). Re-install and have Ahmet re-check the failing items only.
- [ ] Measure frame times on devices: fling scrolling on the iPad at 120 Hz, and typing on the Fold. Record them in the
  README against the spec §6.6 targets.
- [ ] Commit the fixes and the results: `fix(editor-compose): device-pass fixes` and
  `docs(editor-compose): device pass results`.

### Task 8: Docs and memory
- [ ] Update the README with the touch model, the menu, zoom, accessibility and the device results. Append a dated
  entry to `~/.mux/domains/editor.md`, and to `infra.md` for any device or install gotchas. Commit.

## Not in M3b
- **M3c:** gutter markers, block widgets, inline widgets, fold `Replace` rendering, panels, linked views, and moving
  scroll writes out of draw.
