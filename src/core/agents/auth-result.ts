/** The result shape every per-agent auth resolver returns.
 *
 * This is a plain type alias, NOT an interface and NOT a contract. Each agent
 * module keeps its own private `resolve<Kind>Auth` function with its own option
 * type and its own modes; the modules only agree on the shape of the answer, so
 * a caller can hand `env` to a child process without a kind check.
 *
 * Normalized rules for every resolver in `src/core/agents/<kind>/auth.ts`:
 *  - the function is `async`,
 *  - the function returns `AgentAuthResult`,
 *  - the file header states whether the function fails closed (throws when no
 *    credential exists) or fails open.
 */
export type AgentAuthResult<Mode extends string = string> = {
  /** How this session authenticates. Each agent names its own modes. */
  mode: Mode
  /** Extra environment for the agent child process. Empty when the agent reads
   * the broker's own environment. */
  env: Record<string, string>
}
