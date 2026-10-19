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

Each agent is its own CLI, signed in as you are in a terminal. A recipe stops its agents when it ends, and when you press Ctrl-C or send it SIGTERM. Nobody answers an agent's questions here, so a request for permission is refused and a question declined: a pending request never expires, and the turn would wait for ever.

## Same task on three agents

`same-task-three-agents.ts` sends one prompt to Claude Code, Codex and Grok at once. Each works in a git worktree of its own, on a branch `race/<agent>-<time>` made from the commit the repository is on (changes you have not committed are not in it). When all are done it prints a line per agent: what it changed, its branch and its worktree.

```sh
node same-task-three-agents.ts "Add a --verbose flag" --agents claude,codex --repo ~/projects/app
# claude: 2 files changed, 14 insertions(+), 1 deletion(-) (branch race/claude-1760870000000, worktree /tmp/race-k3JfQ2/claude)
# codex: 1 file changed, 9 insertions(+) (branch race/codex-1760870000000, worktree /tmp/race-k3JfQ2/codex)
```

`--agents` takes `claude`, `codex` and `grok` (default: all three). `--repo` is any directory of a git work tree (default: the current one). The branches and worktrees stay, for you to compare with `git diff`, merge or throw away. To remove one: `git worktree remove --force <worktree>`, then `git branch -D <branch>`.

**The agents work without asking**: Claude accepts edits, Codex never asks (its sandbox allows writes in its workspace), Grok approves every request. That is safe only because each one works in a throwaway worktree, and only for your checkout: a worktree is not a sandbox, and Grok's approval lets it run any command as you. Do not give an agent these settings in a checkout you care about.

## A review in CI

`ci-review.ts` has Codex review the diff of the current branch against a base, and prints the review (the agent's last message) to stdout. Codex runs read-only and is never asked anything: it can read the repository, not change it. The exit code is 1 when there is no review: the base is not a commit, Codex could not run or failed, or it said nothing.

```sh
node ci-review.ts --base origin/main
```

`--base` is `origin/main` unless you say otherwise. In CI the Codex CLI must be installed and signed in, and the checkout must have the base (`fetch-depth: 0` in `actions/checkout`).

## A tool that is a function

`deploy-tool.ts` gives an agent a `deploy` tool that is a function in the recipe's own process: an MCP server built with `mcpServer()` and `tool()`, its arguments described with zod. The tool takes `staging` and nothing else, and logs each call with the agent and the session that made it. Claude is allowed to call it without asking (`allowedTools: ["mcp__ops__deploy"]`); Codex runs a host tool without asking.

```sh
node deploy-tool.ts
# deploy called by claude session 7d1c0a52-3f0e-4c0a-9b0e-2f6d2c1b8a11: staging
node deploy-tool.ts "Deploy to staging, then say what you did." --agent codex
```
