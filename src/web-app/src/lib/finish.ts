export type FinishVerifyAction = "merge" | "pr"

/** Whether "Skip tests" may be offered. Hidden only on the PR path when the
 *  repo requires green tests for a PR (skipping would silently defeat it). */
export function canSkipTests(action: FinishVerifyAction, prRequiresGreen: boolean): boolean {
  return !(action === "pr" && prRequiresGreen)
}
