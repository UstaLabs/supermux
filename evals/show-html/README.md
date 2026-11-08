# show_html evals (#104)

Decide, before anything is built, whether agents use **one tool** (`show_html`, which reports render problems) well
enough, or need **two** (`preview_html` + `show_html`), and which instruction wording works across models. The design is
in `docs/superpowers/specs/2026-11-08-show-html-design.md` ("Evals").

No app UI and no broker: each run starts an agent CLI in a throwaway folder with only the stub server attached.

| file | what it is |
|---|---|
| `arms.ts` | the six arms: variants A (one tool), B (preview + show), C (one tool + a "check your result" line) × wordings 1 and 2: tool descriptions and the "Interactive HTML" instructions |
| `prompts.ts` | the 18 fixed prompts, with what each should end with (a card or none); 16–18 force one problem on the first check |
| `server.ts` | the stub MCP server: serves an arm's tools, runs the real check on each page, records `calls.jsonl` and `pages/` |
| `check.ts` | the render check: the page in a sandboxed `blob:` frame at 390 and 720 px; script errors, blank, overflow, endless height; local addresses refused |
| `page-api.ts` | what every page gets first: the `--sm-*` theme variables, a base sheet, `window.supermux` with the click guard |
| `click.ts` | the click round trip as the web app will do it: real clicks through the DevTools protocol, the host's half of the guard |
| `agents.ts` | how each CLI runs one prompt (Claude Code, Codex so far) |
| `run.ts` | the runner: arms × prompts × samples on one agent and model → `results.jsonl` |
| `score.ts`, `report.ts` | scores per run, and the table per model × arm |

```sh
bun evals/show-html/run.ts --agent claude --model claude-haiku-5-5 --out ~/.cache/x/show-html-evals/smoke \
  --home ~/.mux/state/accounts/homes/claude/<account> [--arms A1,B1] [--prompts 01-chart,11-fact] [--samples 1] [--concurrency 2]
bun evals/show-html/report.ts ~/.cache/x/show-html-evals/*/results.jsonl
```

- `--home` picks the account the CLI runs on (`CLAUDE_CONFIG_DIR` / `CODEX_HOME`). The runs spend that account's
  subscription limits: pick one with room (`~/.mux/state/accounts/usage.json`), never the account your own session uses.
- Claude runs have no Bash and load no user settings (`--setting-sources project`), so hooks and plugins don't skew them.
- The runner strips every `MUX_*` variable, so a run can't reach the live broker.
- Tests: `SHOW_HTML_EVALS=1 bun test evals/show-html` runs the ones in real Chrome, Edge or Chromium (`MUX_CHECK_BROWSER` points at another one); without it only the pure ones run, as in CI.
