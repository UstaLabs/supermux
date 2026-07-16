# Native Git Hosting Parity Design

## Goal

Make Git hosting setup on macOS onboarding feel deliberate and match the helpful parts of the web flow: exactly two provider choices, the easiest available authentication path first, and pre-filled token-creation links when a personal access token is needed.

## User Experience

The Git Hosting onboarding step remains optional. The wizard's Continue button stays available even when no Git account is configured.

The shared native add-account sheet presents:

1. Exactly two equal-width provider buttons: GitHub and GitLab. Each button is one accessible control containing the provider logo and name.
2. An authenticated CLI import action when `gh` or `glab` is available for the selected provider.
3. A divider followed by personal-access-token entry.
4. A `Create a pre-filled token ↗` link and the required scope summary directly below the token field.
5. Self-hosted host and HTTPS/SSH transport options inside the existing collapsed disclosure.
6. A provider-specific Connect action.

Switching provider clears token, host, and error state so credentials cannot accidentally be submitted to the wrong service. HTTPS remains the default transport.

## Provider Selector

The current segmented SwiftUI `Picker` is replaced with a custom two-button `HStack`. On macOS, the segmented picker flattens the icon and text within each option into separate segments, so two providers render as four controls. Each custom button owns its complete logo-and-label content and exposes selected state to accessibility.

The selector is shared by onboarding and native Settings because both surfaces use `GitHostingSettingsView`. No onboarding-only duplicate is introduced.

## Token Templates

A pure native helper builds token-creation URLs with `URLComponents` and `URLQueryItem`, matching the existing web behavior:

- GitHub.com uses the fine-grained token page with name `supermux`, the description `Clone, create & push repos from supermux`, and `contents=write` plus `administration=write`.
- Self-hosted GitHub uses the classic token page on the supplied host with the description and `repo,read:org` scopes.
- GitLab.com and self-hosted GitLab use the personal-access-token page on the selected host with the name, description, and `api` scope.

The optional self-hosted API URL is reduced to its host before the token URL is constructed. If no valid token URL can be formed, the link is hidden while the scope guidance remains visible.

## Errors and State

CLI import and token connection dismiss the sheet only after the broker returns a connection. Failure keeps the sheet open and displays an actionable provider-specific error. Existing broker APIs and credential storage remain unchanged.

Changing provider resets token, self-hosted URL, advanced disclosure state, and any prior error. The selected transport resets to HTTPS.

## Architecture

- `apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift` owns the two-button selector, add-sheet state transitions, token link, and pure token-template helper.
- `apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift` specifies provider ordering, SaaS token templates, self-hosted token templates, and malformed-host handling.
- The web implementation remains unchanged because it already has two provider controls and pre-filled token links.

No broker route, persistence schema, credential format, or onboarding navigation rule changes.

## Verification

1. Write native unit tests for the provider model and generated token URLs before production changes.
2. Run the focused macOS tests and full macOS unit suite.
3. Build and install the macOS app on the remote Mac.
4. Open the real onboarding Git Hosting sheet and inspect the screen for exactly two provider controls, the pre-filled-token link, correct selected styling, and a usable layout.
5. Open generated GitHub and GitLab URLs and verify their query parameters.

## Out of Scope

- Redesigning the web Git-hosting page.
- Making Git hosting mandatory during onboarding.
- Adding providers beyond GitHub and GitLab.
- Changing token permissions or forge APIs.
- Redesigning the accounts list after a connection exists.
