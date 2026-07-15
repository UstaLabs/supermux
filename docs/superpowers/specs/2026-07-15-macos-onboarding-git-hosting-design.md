# macOS Onboarding Git Hosting Design

## Goal

Let a first-time macOS host user configure GitHub or GitLab during native onboarding, before pairing other devices, without making a Git-hosting account mandatory.

## User Experience

The macOS onboarding sequence becomes:

1. Welcome
2. Agents
3. Git Hosting
4. Connectivity
5. Done

The new Git Hosting step embeds the existing `GitHostingSettingsView` with the local onboarding broker. It therefore offers the same configuration paths as Settings: importing an authenticated `gh` or `glab` CLI account, connecting with a personal access token, selecting HTTPS or SSH transport, and specifying a self-hosted GitHub or GitLab instance.

Git hosting is optional. The wizard's Continue button remains enabled when the user has no connection, while Back and Continue follow the normal sequential navigation. Loading or connection errors remain visible within `GitHostingSettingsView` and do not trap the user in onboarding.

## Architecture

Add a `gitHosting` case to `MacOnboardingStep` between `agents` and `connectivity`, with the title `Git Hosting`. `MacHostWizard` renders `GitHostingSettingsView(broker: broker)` for that case. If the local broker is not yet available, it uses the same connecting progress state as the Agents step.

No new broker endpoint, persistence model, or Git credential handling is introduced. The existing forge APIs and credential storage remain the single source of truth, and accounts configured during onboarding appear later in Settings and the new-session project picker automatically.

## Navigation and Completion Rules

- Welcome continues to require a ready local broker.
- Agents continues to require an authenticated supported agent or installed OpenCode.
- Git Hosting is always allowed to advance once the local broker exists; configuring an account is not required.
- Connectivity continues to require a ready host and retains its pairing behavior.
- Done continues to mark broker onboarding complete and perform the keep-alive handoff.

## Error Handling

The embedded Git-hosting screen owns loading, import, token-validation, and disconnect errors as it already does in Settings. A failure leaves the user on the step with retry controls available, but Continue remains available so an unavailable Git provider cannot block host setup.

## Testing

Update the macOS onboarding step-order test to expect all five titles in the new order. Add a focused assertion for the Git Hosting step's optional advance rule so a future refactor cannot accidentally make account configuration mandatory. Run the macOS test target and confirm the app target compiles.

## Out of Scope

- Changing web, iOS, Android, or Compose Desktop onboarding.
- Redesigning `GitHostingSettingsView`.
- Requiring or automatically importing a Git account.
- Changing forge authentication, tokens, transports, or broker APIs.
