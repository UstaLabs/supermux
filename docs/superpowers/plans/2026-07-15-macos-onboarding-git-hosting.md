# macOS Onboarding Git Hosting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional Git Hosting step to native macOS onboarding between Agents and Connectivity.

**Architecture:** Extend the existing `MacOnboardingStep` state model and make its advance rule directly testable. Render the existing `GitHostingSettingsView` with the already-created local `BrokerSession`, preserving the forge APIs and credential storage as the sole implementation of Git account setup.

**Tech Stack:** Swift 6, SwiftUI, XCTest, XcodeGen, remote macOS `xcodebuild`

---

## File Structure

- Modify `apps/iosApp/Supermux/Host/MacHostWizard.swift`: add the step model, optional advance rule, embedded Git-hosting pane, and sequential navigation.
- Modify `apps/iosApp/SupermuxTests/MacHostTests.swift`: specify the five-step order and prove Git hosting does not require an account.

No new files, broker endpoints, or persistence types are needed.

### Task 1: Add the optional Git Hosting onboarding step

**Files:**
- Modify: `apps/iosApp/SupermuxTests/MacHostTests.swift:7-9`
- Modify: `apps/iosApp/Supermux/Host/MacHostWizard.swift:235-249`
- Modify: `apps/iosApp/Supermux/Host/MacHostWizard.swift:307-426`
- Modify: `apps/iosApp/Supermux/Host/MacHostWizard.swift:468-490`

- [ ] **Step 1: Write the failing step-order and optionality tests**

Replace the existing onboarding-order test and add the optionality test in `MacHostPolicyTests`:

```swift
func testOnboardingStepsIncludeOptionalGitHostingBeforeConnectivity() {
    XCTAssertEqual(
        MacOnboardingStep.allCases.map(\.title),
        ["Welcome", "Agents", "Git Hosting", "Connectivity", "Done"]
    )
}

func testGitHostingCanAdvanceWithoutAConfiguredAccountOnceBrokerIsReady() {
    XCTAssertFalse(
        MacOnboardingStep.gitHosting.canAdvance(
            hasBroker: false,
            agentsReady: false,
            hostReady: false
        )
    )
    XCTAssertTrue(
        MacOnboardingStep.gitHosting.canAdvance(
            hasBroker: true,
            agentsReady: false,
            hostReady: false
        )
    )
}
```

The API intentionally has no `forgeConfigured` input: advancing from Git Hosting depends only on the local broker being available.

- [ ] **Step 2: Sync to the remote Mac and verify the new tests fail**

Run from the repository root:

```bash
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux-mac && mkdir -p ~/supermux-mac && tar -xzf - -C ~/supermux-mac'
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
  -only-testing:SupermuxMacTests/MacHostPolicyTests \
  -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO'
```

Expected: FAIL to compile because `MacOnboardingStep.gitHosting` and `canAdvance` do not exist. This is the required RED result.

- [ ] **Step 3: Add the step and its advance policy**

Replace `MacOnboardingStep` with:

```swift
enum MacOnboardingStep: Int, CaseIterable {
    case welcome
    case agents
    case gitHosting
    case connectivity
    case done

    var title: String {
        switch self {
        case .welcome: return "Welcome"
        case .agents: return "Agents"
        case .gitHosting: return "Git Hosting"
        case .connectivity: return "Connectivity"
        case .done: return "Done"
        }
    }

    func canAdvance(hasBroker: Bool, agentsReady: Bool, hostReady: Bool) -> Bool {
        switch self {
        case .welcome, .gitHosting, .done: return hasBroker
        case .agents: return agentsReady
        case .connectivity: return hostReady
        }
    }
}
```

- [ ] **Step 4: Embed the existing Git-hosting screen**

Add this case between `.agents` and `.connectivity` in `MacHostWizard.content`:

```swift
case .gitHosting:
    if let broker {
        GitHostingSettingsView(broker: broker)
    } else {
        ProgressView("Connecting to the local host…")
            .controlSize(.large)
    }
```

This reuses CLI import, PAT entry, self-hosted configuration, transport selection, loading, and error behavior without duplicating forge logic.

- [ ] **Step 5: Route the footer and navigation through the new step**

Replace the view's `canAdvance` body with:

```swift
private var canAdvance: Bool {
    step.canAdvance(
        hasBroker: broker != nil,
        agentsReady: agentsReady,
        hostReady: coordinator.state.isReady
    )
}
```

Update `advance()` to include the new transition:

```swift
private func advance() {
    finishError = nil
    switch step {
    case .welcome: step = .agents
    case .agents: step = .gitHosting
    case .gitHosting: step = .connectivity
    case .connectivity: step = .done
    case .done: Task { await completeSetup() }
    }
}
```

`moveBack()` already decrements the raw step value, so it automatically follows the new five-step order.

- [ ] **Step 6: Re-sync and verify the focused macOS tests pass**

Run:

```bash
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux-mac && mkdir -p ~/supermux-mac && tar -xzf - -C ~/supermux-mac'
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
  -only-testing:SupermuxMacTests/MacHostPolicyTests \
  -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO'
```

Expected: `MacHostPolicyTests` passes and the command ends with `** TEST SUCCEEDED **`.

- [ ] **Step 7: Run the full macOS unit suite and compile the app**

Run after the synced focused test:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && \
  xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
  -skip-testing:SupermuxMacUITests \
  -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO && \
  xcodebuild build -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
  -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO'
```

Expected: the test phase ends with `** TEST SUCCEEDED **` and the compile phase ends with `** BUILD SUCCEEDED **`.

- [ ] **Step 8: Review the diff and commit the feature**

Run locally:

```bash
git diff --check
git diff -- apps/iosApp/Supermux/Host/MacHostWizard.swift apps/iosApp/SupermuxTests/MacHostTests.swift
git add apps/iosApp/Supermux/Host/MacHostWizard.swift apps/iosApp/SupermuxTests/MacHostTests.swift
git commit -m "feat(mac): add Git hosting to onboarding"
```

Expected: only the wizard state/rendering and focused macOS tests are committed.
