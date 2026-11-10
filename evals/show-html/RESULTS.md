# show_html evals: results (#104, 2026-11-10)

## Decision

These choices follow from the runs below:
- **One tool, `show_html`**, with variant C's line telling the agent to check the result it gets back. That is Ahmet's option B. Two tools (variant B, `preview_html` then `show_html`) made no more cards and cost about 30% more tokens per run.
- **Instruction wording 1**, the terse rules. Wording 2, the same rules with reasons, made no difference on Haiku or Sonnet.
- **To fix before building:** the tool description, for comparison and table prompts. On those, models answer in markdown instead of making a card. Haiku does this more than Sonnet. Card-when-wanted was 51–56% on Haiku and 67–69% on Sonnet.
- **Never a card when none is wanted:** 18 of 18 in every arm, on every model.
- **Repair after a forced problem:**
  - Sonnet: 6 of 6 in both arms.
  - Haiku: 75–100%, on 4–8 cases per arm.
- **A click sends the choice:**
  - Sonnet: 6 of 6.
  - Haiku: 3 of 6. On "let me pick the values" prompts it often builds the page with no button.

## Runs
The runs are Haiku Stage 1 and Stage 2, Sonnet phase 3 (arm C) and OpenCode's free model in Stage 1 (189 of 216 runs; it hit its free limit twice). Each prompt was run 3 times. "Held out" rows are prompts the wordings were not tuned on. Costs are API-equivalent; the runs used subscription accounts.

On cost:
- Sonnet: about 53k tokens and $0.05 per run.
- Haiku: about 65k tokens and $0.0016 per run.
- The Opus half of phase 3 is held, because the conclusion is already firm.

Runs where the model never answered (a usage limit, a stalled provider) are kept out of the table: they're in `no-answer.jsonl`.

| model | arm | card when asked | no card when not | 1st page clean | repaired after a problem | theme + transparent | click sends | previewed first | tokens/run | cost | failed runs |
|---|---|---|---|---|---|---|---|---|---|---|---|
| claude claude-haiku-5-5 | A1 | 23/45 | 18/18 | 100% | 3/4 | 100% | 3/6 | – | 62k | $0.22 | 0 |
| claude claude-haiku-5-5 | A1 · held out | 2/6 | 3/3 | 100% | – | 100% | – | – | 51k | $0.02 | 0 |
| claude claude-haiku-5-5 | A2 | 20/45 | 18/18 | 100% | 4/4 | 100% | 3/6 | – | 65k | $0.23 | 0 |
| claude claude-haiku-5-5 | A2 · held out | 0/6 | 3/3 | – | – | – | – | – | 43k | $0.02 | 0 |
| claude claude-haiku-5-5 | B1 | 22/45 | 18/18 | 85% | 6/7 | 89% | 4/6 | 19/19 | 81k | $0.26 | 0 |
| claude claude-haiku-5-5 | B1 · held out | 0/6 | 3/3 | – | – | – | – | – | 42k | $0.02 | 0 |
| claude claude-haiku-5-5 | C1 | 25/45 | 18/18 | 100% | 5/6 | 91% | 3/6 | – | 67k | $0.23 | 0 |
| claude claude-haiku-5-5 | C1 · held out | 0/6 | 3/3 | – | – | – | – | – | 35k | $0.02 | 0 |
| claude claude-haiku-5-5 | C2 | 24/45 | 18/18 | 85% | 6/8 | 90% | 3/6 | – | 67k | $0.24 | 0 |
| claude claude-haiku-5-5 | C2 · held out | 2/6 | 3/3 | 100% | – | 100% | – | – | 53k | $0.02 | 0 |
| claude claude-sonnet-5-5 | C1 | 30/45 | 18/18 | 100% | 6/6 | 100% | 6/6 | – | 52k | $3.20 | 0 |
| claude claude-sonnet-5-5 | C1 · held out | 3/6 | 3/3 | 100% | – | 100% | – | – | 53k | $0.38 | 0 |
| claude claude-sonnet-5-5 | C2 | 31/45 | 18/18 | 100% | 6/6 | 90% | 6/6 | – | 54k | $3.29 | 0 |
| claude claude-sonnet-5-5 | C2 · held out | 3/6 | 3/3 | 100% | – | 100% | – | – | 55k | $0.39 | 0 |
| opencode opencode/deepseek-v4.1-flash-free | A1 | 40/45 | 18/18 | 85% | 14/14 | 100% | 5/6 | – | 39k | $0.00 | 0 |
| opencode opencode/deepseek-v4.1-flash-free | A1 · held out | 5/6 | 3/3 | 83% | 0/1 | 100% | – | – | 35k | $0.00 | 0 |
| opencode opencode/deepseek-v4.1-flash-free | B1 | 34/40 | 18/18 | 97% | 8/10 | 94% | 5/5 | 34/34 | 70k | $0.00 | 5 |
| opencode opencode/deepseek-v4.1-flash-free | B1 · held out | 3/6 | 3/3 | 100% | – | 100% | – | 3/3 | 40k | $0.00 | 0 |
| opencode opencode/deepseek-v4.1-flash-free | C1 | 25/29 | 14/14 | 100% | – | 100% | 6/6 | – | 39k | $0.00 | 2 |

## How to reproduce
Use `evals/show-html/README.md`. The raw results are in `~/.cache/x/show-html-evals/{stage1-haiku,haiku2,sonnet3,stage1-opencode}/results.jsonl` on the dev box.
