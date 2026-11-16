# MCP vs CLI evals (#156)

Does an agent drive supermux as well through a `supermux` CLI as through the mux-shim MCP tools, and for fewer
tokens and less time? Design, every round's results and what the transcripts showed: `/p/cli-156/` (the page lives in
`~/.mux/prototypes/156-cli/index.html`).

| file | what it is |
|---|---|
| `broker.ts` | a stand-in broker for ONE run, in a Docker container with no network: the real frame protocol and the real `shimTools()` list (all 38 tools, or the 3 kept ones in the CLI arm), over a seeded fake world |
| `seed.ts` | the fake world: sessions, transcripts, memory, files the user "sent", the workdir |
| `tasks.ts` | 21 tasks in Ahmet's voice, graded on the end state (T tuning, H held out, N written blind and frozen) |
| `cli.ts` | the eval's CLI prototype; the product CLI is `src/cli/agent.ts` |
| `agents.ts` | how Claude, Grok and OpenCode run one task, with a shell, the real shim as their MCP server |
| `run.ts` | tasks × arms × samples; each run is sandboxed (bwrap: read-only /, live ~/.mux hidden, no Docker socket, a fake issues token, tunnel binaries stubbed) |
| `report.ts` | success, tokens, time and steps per model × arm |
| `try.sh` | `source` it to try the prototype by hand against a throwaway broker |

Before running a provider, read its notes in `~/.mux/domains/infra*.md`: Grok must run with `GROK_HOME` set to the
working account (not the default `~/.grok`), and OpenCode's free model runs out after ~40 runs. The runner's
`--max-quota` guard reads `usage.json`, which does not track Grok reliably.
