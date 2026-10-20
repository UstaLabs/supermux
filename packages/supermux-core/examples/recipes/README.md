# supermux recipes

Three programs for scripts and CI, each one a single file on `@supermux/core`: copy one and change it.

## Run them

In this repository (Node 22.18 or later runs a `.ts` file as it is):

```sh
bun install
bun run build:core
cd packages/supermux-core/examples/recipes
node ci-review.ts --base origin/main
```

On its own:

```sh
npx degit UstaLabs/supermux/packages/supermux-core/examples/recipes my-recipes
cd my-recipes && npm install && node ci-review.ts
```

Each agent is its own CLI, signed in as you are in a terminal, and it runs with the environment of the recipe. A recipe stops its agents when it ends, and when you press Ctrl-C or send it SIGTERM. Nobody answers an agent's questions here, so a recipe refuses a request for permission and declines a question, and says so on stderr (`refused: <tool>`, `declined: <question>`): a pending request never expires, and the turn would wait for ever.

Where things live: a recipe keeps what it makes under `~/.supermux-recipes/<recipe>-<folder>-<hash>/<time>/`, a folder for you alone (mode 0700) named for the recipe, the repository or working directory it runs in and a hash of that path, and for the run. It is never in the temporary directory: an agent can write there (Codex can, by default) and could swap what the recipe keeps there for a link. (A socket whose path would be too long for the state directory, a keeper's or a host tool's, is put by the core elsewhere: in the temporary directory, else in `/tmp`, and in `$XDG_RUNTIME_DIR` only after them.) `ci-review.ts` and `deploy-tool.ts` keep only the state of their session, `state/`, and remove the folder of the run when they end. `same-task-three-agents.ts` keeps the worktrees too: see below for taking the work and cleaning up. The state is removed once the core has closed, and not before: if it could not (a keeper that does not answer may still be running its agent), a recipe removes nothing, and says where the state is, which processes to stop (`keeperPid` and `agentPid` in `state/keepers/*/status.json`) and, where it applies, how to remove the folder.

## Same task on three agents

> **Warning: these agents work without asking, and a worktree is not a sandbox.** It keeps their edits off your checkout, and nothing more.
>
> - **Claude Code** edits files in its worktree without asking. What else it asks for (a shell command, say) is refused.
> - **Codex** runs commands without asking, in its sandbox: it can read every file you can read, and write in its worktree and in the temporary directories (the recipe keeps its own files elsewhere).
> - **Grok** runs on auto-approve: any command it wants to run, it runs, as you, anywhere on this machine. So it is left out unless you ask for it (`--agents claude,codex,grok`), and the recipe says so when it starts.
>
> The recipe refuses a repository inside the temporary directories, where an agent could write to its `.git`. Do not run it with secrets in the environment you would not hand to an agent.

`same-task-three-agents.ts` sends one prompt to Claude Code and Codex at once (and Grok, if you ask for it). Each works in a git worktree of its own, on a branch `race/<agent>-<time>` made from the commit the repository is on (changes you have not committed are not in it). When all are done the agents are stopped, and what each one changed is committed on its branch, so the branch keeps it when the worktree goes. The recipe prints a line per agent: what it changed, its branch and its worktree. Ctrl-C or SIGTERM ends the run the same way: the agents stop, what each had done so far is committed on its branch, the lines and the commands below are printed, and the recipe exits with 130 or 143. An agent that moved off its branch (it checked out another one, or detached HEAD) has nothing committed: its line says `not kept`, and its worktree is as it left it, for you to look at (the recipe does not move HEAD back). A repository the agent made inside its worktree is left out of the commit, and its line names it: as a gitlink it would point at commits nobody else has.

```sh
node same-task-three-agents.ts "Add a --verbose flag" --repo ~/projects/app
# claude: 2 files changed, 14 insertions(+), 1 deletion(-) (branch race/claude-1760870000000, worktree /home/ana/.supermux-recipes/same-task-three-agents-app-3f2a9c1b/1760870000000/claude)
# codex: 1 file changed, 9 insertions(+) (branch race/codex-1760870000000, worktree /home/ana/.supermux-recipes/same-task-three-agents-app-3f2a9c1b/1760870000000/codex)
```

`--agents` takes `claude`, `codex` and `grok` (default: `claude,codex`). `--repo` is any directory of a git work tree (default: the current one). The worktrees are `<agent>/` in the folder of the run, `~/.supermux-recipes/same-task-three-agents-<repository>-<hash>/<time>/`, and the sessions' state, `state/`, is removed when the recipe ends. It prints, on stderr, the commands to take an agent's work (`git merge <branch>`) and to remove the worktrees, the branches and the folder of the run, with the real paths and names: the branches keep the work, so the worktrees can go first. Nothing under `~/.supermux-recipes` is needed once you have what you want from the branches: `rm -r` it, and `git worktree prune` in the repository clears its list of the worktrees.

The commits are by the author git has configured, or by `supermux recipes <recipes@supermux.invalid>` when it has none. The recipe's git commands run with hooks and fsmonitor off (so none of the repository's own hooks runs for the worktrees or the commits), on the git directories it recorded before the agents started, and they leave out the submodules the base records: what an agent leaves in its worktree (a `.git` of its own, say) cannot make them run a command.

## A review in CI

> **Warning: read-only limits what Codex writes, not what it reads or sees.** It can read any file the CI user can read, and it runs with the environment of the job, tokens included. The branch under review is untrusted input: its files and its diff can carry instructions that steer the review (prompt injection), up to making it repeat what it read. Do not run this on code from authors you do not trust while the job holds secrets, and do not post the review anywhere those secrets must not go.

`ci-review.ts` has Codex review the diff of the current branch against a base, and prints the review (the agent's last message) to stdout. Codex runs read-only and is never asked anything: it can read the repository, not change it. The exit code is 1 when there is no review: the base is not a commit, Codex could not run or failed, or it said nothing.

```sh
node ci-review.ts --base origin/main
```

`--base` is `origin/main` unless you say otherwise. In CI the Codex CLI must be installed and signed in, and the checkout must have the base (`fetch-depth: 0` in `actions/checkout`).

## A tool that is a function

`deploy-tool.ts` gives an agent a `deploy` tool that is a function in the recipe's own process: an MCP server built with `mcpServer()` and `tool()`, its arguments described with zod. The tool takes `staging` and nothing else, and logs each call with the agent and the session that made it. Claude is allowed to call it without asking (`allowedTools: ["mcp__ops__deploy"]`) and is refused anything else it asks for. Codex (`--agent codex`) runs read-only and is never asked, and the core lets it call a host tool all the same.

```sh
node deploy-tool.ts
# deploy called by claude session 7d1c0a52-3f0e-4c0a-9b0e-2f6d2c1b8a11: staging
node deploy-tool.ts "Deploy to staging, then say what you did." --agent codex
```
