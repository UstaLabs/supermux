/// Whether "Skip tests" may be offered. Hidden only on the PR path when the repo
/// requires green tests for a PR (skipping would silently defeat that policy).
func canSkipTests(action: String, prRequiresGreen: Bool) -> Bool {
    !(action == "pr" && prRequiresGreen)
}
