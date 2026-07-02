# macOS Desktop Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a true native macOS app (`Supermux.app`) at full feature parity with the iOS app, per `docs/superpowers/specs/2026-07-02-macos-desktop-client-design.md`.

**Architecture:** A new `SupermuxMac` XcodeGen target shares the existing `apps/iosApp/Supermux/` SwiftUI sources with the iOS target; platform divergence is handled by a new `PlatformShims.swift` compatibility layer plus `#if os(iOS)` / `#if os(macOS)` guards (the codebase has ZERO existing conditionals — these tasks introduce the pattern). The KMP shared module gains a `macosArm64()` target; all Darwin actuals already live in `appleMain`, so the framework builds with no new Kotlin code. Zero broker changes.

**Tech Stack:** SwiftUI (AppKit-backed), SwiftTerm 1.13 (has a native AppKit `TerminalView`), WKWebView + CodeMirror, KMP `Shared.framework` via SKIE, XcodeGen, remote-Mac headless builds over SSH.

---

## Ground rules (read before Task 1)

- **The iOS target must build green after every task.** Shared sources change constantly here; a task is not done until the iOS simulator build passes. The mac build is ALLOWED to be red until Task 12 (the green-build milestone) — after Task 12 both must stay green.
- **All Apple builds run on the remote Mac** (`ssh mac`, Tailscale `100.121.185.86`, user `ahmet`, Xcode 26.5). Kotlin/Native Apple targets are disabled on this Linux host.
- **Every ssh build command MUST start with `source ~/ios-build-env.sh`** or gradle/xcodegen/java are "not found".
- **Sync with tar-over-ssh, never rsync** (macOS ships openrsync, which breaks). Always sync the WHOLE repo work-tree (the KMP pre-build phase reads `apps/` as its gradle root):

```bash
cd <repo-root>
tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux-mac && mkdir -p ~/supermux-mac && tar -xzf - -C ~/supermux-mac'
```

- **Long builds:** macOS has no `setsid`/`timeout`. Run `nohup ssh … xcodebuild … > log 2>&1 &` and poll the log for `BUILD SUCCEEDED` / `BUILD FAILED` / `** TEST`.
- **iOS-green check** (used by many tasks; referred to as "the iOS build check"):

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  nohup xcodebuild -scheme Supermux -sdk iphonesimulator26.5 \
    -destination "generic/platform=iOS Simulator" -derivedDataPath build/dd-ios \
    ARCHS=arm64 EXCLUDED_ARCHS=x86_64 CODE_SIGNING_ALLOWED=NO build > ~/supermux-mac/ios-build.log 2>&1 &'
# poll until done:
ssh mac 'tail -3 ~/supermux-mac/ios-build.log; grep -c "BUILD SUCCEEDED" ~/supermux-mac/ios-build.log'
```

- **Mac build check** (the analog; used from Task 2 on):

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  nohup xcodebuild -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
    -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO build > ~/supermux-mac/mac-build.log 2>&1 &'
ssh mac 'tail -5 ~/supermux-mac/mac-build.log; grep -c "BUILD SUCCEEDED" ~/supermux-mac/mac-build.log'
```

  (`CODE_SIGNING_ALLOWED=NO` is fine for compile checks. For RUNNING the app on the Mac use ad-hoc signing instead — entitled apps crash on launch unsigned: `CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=YES CODE_SIGNING_ALLOWED=YES` — and note APNs registration will fail ad-hoc; that's expected in dev runs.)
- **Commits happen on this Linux host** (the Mac copy is disposable). Edit locally → tar-sync → build remotely → commit locally.
- `/tmp` on this host has a user quota (EDQUOT makes Bash return "exit 1, no output"). Redirect big outputs to `~/.cache/…`, e.g. `cmd > /home/ahmet/.cache/out.txt 2>&1`.

---

### Task 1: KMP `macosArm64` target

**Files:**
- Modify: `apps/shared/build.gradle.kts:19-27`

- [ ] **Step 1: Add the target.** In `apps/shared/build.gradle.kts`, extend the Apple-targets list (lines 19-22) to include `macosArm64()`:

```kotlin
    listOf(
        iosArm64(), iosSimulatorArm64(),
        watchosArm64(), watchosSimulatorArm64(),
        macosArm64(),
    ).forEach { t ->
        t.binaries.framework {
            baseName = "Shared"
            isStatic = false
        }
    }
```

No other Kotlin change is needed: there is NO `iosMain` source set — all Darwin actuals live in `appleMain` (`SecureTokenStore.apple.kt`, `Inflate.apple.kt` (zlib cinterop — zlib exists on macOS), `IosClient.kt` (ktor Darwin engine — works on macOS), `PushCrypto.apple.kt`), and `macosArm64` joins `appleMain` automatically via the default hierarchy template. The one `TODO()` stub (`openSealedPush` in `PushCrypto.apple.kt`) is dead code on iOS too (the Swift NSE has its own implementation) — leave it.

- [ ] **Step 2: Linux sanity — JVM tests still green.** Run on this host:

```bash
cd apps && ./gradlew --no-daemon :shared:jvmTest > /home/ahmet/.cache/kmp-jvm.log 2>&1; tail -5 /home/ahmet/.cache/kmp-jvm.log
```

Expected: `BUILD SUCCESSFUL` (Apple targets are disabled on Linux via `kotlin.native.ignoreDisabledTargets`, so adding macosArm64 must not break the Linux build).

- [ ] **Step 3: Mac risk gate — the framework actually links for macOS.** Tar-sync (Ground rules), then:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps && ./gradlew --no-daemon :shared:linkDebugFrameworkMacosArm64' 
```

Expected: `BUILD SUCCESSFUL`, framework at `~/supermux-mac/apps/shared/build/bin/macosArm64/debugFramework/Shared.framework`. If SKIE errors on the macOS target, STOP and report — that invalidates the architecture assumption (it shouldn't: macosArm64 is a standard SKIE-supported target).

- [ ] **Step 4: Commit.**

```bash
git add apps/shared/build.gradle.kts
git commit -m "feat(shared): add macosArm64 KMP target for the macOS desktop client"
```

---

### Task 2: XcodeGen `SupermuxMac` target, entitlements, and the compile-audit baseline

**Files:**
- Modify: `apps/iosApp/project.yml`
- Create: `apps/iosApp/Supermux/SupermuxMac.entitlements`
- Create: `apps/iosApp/SupermuxPushNSE/SupermuxMacPushNSE.entitlements`

- [ ] **Step 1: Deployment target.** In `project.yml` `options.deploymentTarget` (lines 10-12), add:

```yaml
    macOS: "26.0"
```

- [ ] **Step 2: Mac app entitlements.** Create `apps/iosApp/Supermux/SupermuxMac.entitlements`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<!-- Mac App Store requires the App Sandbox. The app is a pure client:
	     outbound network + mic (dictation/voice) are its only capabilities. -->
	<key>com.apple.security.app-sandbox</key>
	<true/>
	<key>com.apple.security.network.client</key>
	<true/>
	<key>com.apple.security.device.audio-input</key>
	<true/>
	<key>aps-environment</key>
	<string>development</string>
	<!-- Same push-keypair sharing scheme as iOS: the NSE reads the P-256 key
	     the app generates (PushKeypair.swift uses this exact group). -->
	<key>keychain-access-groups</key>
	<array>
		<string>57L7J9XA89.dev.supermux.app.push</string>
	</array>
</dict>
</plist>
```

- [ ] **Step 3: Mac NSE entitlements.** Create `apps/iosApp/SupermuxPushNSE/SupermuxMacPushNSE.entitlements`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.security.app-sandbox</key>
	<true/>
	<key>keychain-access-groups</key>
	<array>
		<string>57L7J9XA89.dev.supermux.app.push</string>
	</array>
</dict>
</plist>
```

- [ ] **Step 4: Add the mac targets to `project.yml`** after the `SupermuxPushNSE` target block (line 194). Notes baked into the YAML: same bundle id as iOS (`dev.supermux.app`) for App Store **universal purchase**; `PRODUCT_NAME`/module stays `Supermux` so `@testable import Supermux` and the `.app` name keep working (per-platform build dirs make the duplicate module name safe); Watch code and the iOS-only camera QR flow are excluded; the mac NSE reuses the iOS NSE sources verbatim (pure CryptoKit — cross-platform).

```yaml
  SupermuxMac:
    type: application
    platform: macOS
    sources:
      - path: Supermux
        excludes:
          - "EditorWeb/**"
          - "Watch/**"            # WatchConnectivity does not exist on macOS
      - path: Supermux/EditorWeb
        type: folder
        buildPhase: resources
    dependencies:
      - package: SwiftTerm
      - target: SupermuxMacPushNSE
        embed: true
    info:
      path: Supermux/InfoMac.plist
      properties:
        CFBundleDisplayName: supermux
        CFBundleVersion: "$(CURRENT_PROJECT_VERSION)"
        CFBundleShortVersionString: "$(MARKETING_VERSION)"
        LSApplicationCategoryType: public.app-category.developer-tools
        CFBundleURLTypes:
          - CFBundleURLName: dev.supermux.connect
            CFBundleURLSchemes:
              - supermux
        NSMicrophoneUsageDescription: supermux records voice messages you send to your agents.
        NSSpeechRecognitionUsageDescription: supermux uses on-device speech recognition to turn your voice into editable text.
        NSLocalNetworkUsageDescription: Supermux connects to the broker server running on your own computer when you reach it by its LAN address (for example 192.168.1.50). It does not scan or collect information about other devices on your network.
        ITSAppUsesNonExemptEncryption: false
        NSAppTransportSecurity:
          NSAllowsArbitraryLoads: true
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.supermux.app
        PRODUCT_NAME: Supermux
        PRODUCT_MODULE_NAME: Supermux
        MACOSX_DEPLOYMENT_TARGET: "26.0"
        CODE_SIGN_ENTITLEMENTS: Supermux/SupermuxMac.entitlements
        FRAMEWORK_SEARCH_PATHS:
          - $(inherited)
          - $(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
        OTHER_LDFLAGS:
          - $(inherited)
          - -framework
          - Shared
        LD_RUNPATH_SEARCH_PATHS:
          - $(inherited)
          - "@executable_path/../Frameworks"
    preBuildScripts:
      - name: Build Kotlin Shared.framework (embed and sign)
        basedOnDependencyAnalysis: false
        script: |
          set -e
          source ~/ios-build-env.sh 2>/dev/null || true
          export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)}"
          cd "$SRCROOT/.."
          ./gradlew --no-daemon :shared:embedAndSignAppleFrameworkForXcode

  SupermuxMacPushNSE:
    # macOS twin of SupermuxPushNSE: same principal class, same shared crypto
    # sources (CryptoKit is cross-platform), its own platform + entitlements.
    type: app-extension
    platform: macOS
    sources:
      - path: SupermuxPushNSE/NotificationService.swift
      - path: Supermux/Push/PushCrypto.swift
      - path: Supermux/Push/PushKeypair.swift
    info:
      path: SupermuxPushNSE/InfoMac.plist
      properties:
        CFBundleDisplayName: SupermuxMacPushNSE
        CFBundleVersion: "$(CURRENT_PROJECT_VERSION)"
        CFBundleShortVersionString: "$(MARKETING_VERSION)"
        NSExtension:
          NSExtensionPointIdentifier: com.apple.usernotifications.service
          NSExtensionPrincipalClass: $(PRODUCT_MODULE_NAME).NotificationService
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.supermux.app.mac-push-nse
        MACOSX_DEPLOYMENT_TARGET: "26.0"
        CODE_SIGN_ENTITLEMENTS: SupermuxPushNSE/SupermuxMacPushNSE.entitlements
        APPLICATION_EXTENSION_API_ONLY: "YES"
```

- [ ] **Step 5: Mac scheme.** In the `schemes:` block (line 210), add:

```yaml
  SupermuxMac:
    build:
      targets:
        SupermuxMac: all
    run:
      config: Debug
    archive:
      config: Release
```

- [ ] **Step 6: Verify xcodegen accepts it + capture the audit baseline.** Tar-sync, then:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate'
```

Expected: `Created project at .../Supermux.xcodeproj` (no YAML errors). Then run the Mac build check (Ground rules) — expected **BUILD FAILED** with many UIKit-related errors. Capture the error inventory (this is the audit artifact Tasks 3-12 burn down):

```bash
ssh mac 'grep -E "error:" ~/supermux-mac/mac-build.log | sort | uniq -c | sort -rn' > /home/ahmet/.cache/mac-audit-baseline.txt
wc -l /home/ahmet/.cache/mac-audit-baseline.txt
```

- [ ] **Step 7: Verify iOS is untouched.** Run the iOS build check. Expected: `BUILD SUCCEEDED`.

- [ ] **Step 8: Commit.**

```bash
git add apps/iosApp/project.yml apps/iosApp/Supermux/SupermuxMac.entitlements apps/iosApp/SupermuxPushNSE/SupermuxMacPushNSE.entitlements
git commit -m "feat(mac): SupermuxMac + SupermuxMacPushNSE XcodeGen targets, sandbox entitlements"
```

---

### Task 3: `PlatformShims.swift` — the compatibility layer

**Files:**
- Create: `apps/iosApp/Supermux/App/PlatformShims.swift` (compiled into BOTH app targets automatically — it lives under `Supermux/`)

- [ ] **Step 1: Create the file** with this complete content:

```swift
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// MARK: - Platform typealiases
// One vocabulary for both UIKit and AppKit. Files that today `import UIKit`
// switch to these names and stop importing UIKit directly.

#if canImport(UIKit)
typealias PlatformView = UIView
typealias PlatformColor = UIColor
typealias PlatformFont = UIFont
typealias PlatformImage = UIImage
#else
typealias PlatformView = NSView
typealias PlatformColor = NSColor
typealias PlatformFont = NSFont
typealias PlatformImage = NSImage
#endif

// MARK: - PlatformViewRepresentable
// Write `makePlatformView`/`updatePlatformView` ONCE; the conditional extension
// maps it onto UIViewRepresentable or NSViewRepresentable.

#if canImport(UIKit)
protocol PlatformViewRepresentable: UIViewRepresentable {
    associatedtype PlatformViewType: UIView
    func makePlatformView(context: Context) -> PlatformViewType
    func updatePlatformView(_ view: PlatformViewType, context: Context)
}
extension PlatformViewRepresentable where UIViewType == PlatformViewType {
    func makeUIView(context: Context) -> PlatformViewType { makePlatformView(context: context) }
    func updateUIView(_ view: PlatformViewType, context: Context) { updatePlatformView(view, context: context) }
}
#else
protocol PlatformViewRepresentable: NSViewRepresentable {
    associatedtype PlatformViewType: NSView
    func makePlatformView(context: Context) -> PlatformViewType
    func updatePlatformView(_ view: PlatformViewType, context: Context)
}
extension PlatformViewRepresentable where NSViewType == PlatformViewType {
    func makeNSView(context: Context) -> PlatformViewType { makePlatformView(context: context) }
    func updateNSView(_ view: PlatformViewType, context: Context) { updatePlatformView(view, context: context) }
}
#endif

// MARK: - Semantic colors (UIColor names ↔ NSColor names)

extension PlatformColor {
    static var smLabel: PlatformColor {
        #if canImport(UIKit)
        .label
        #else
        .labelColor
        #endif
    }
    static var smSecondaryLabel: PlatformColor {
        #if canImport(UIKit)
        .secondaryLabel
        #else
        .secondaryLabelColor
        #endif
    }
    /// iOS `.tertiarySystemBackground` — closest AppKit analog for a raised card fill.
    static var smTertiaryBackground: PlatformColor {
        #if canImport(UIKit)
        .tertiarySystemBackground
        #else
        .underPageBackgroundColor
        #endif
    }
    /// iOS `.tertiarySystemFill` — subtle fill for pills/chips.
    static var smTertiaryFill: PlatformColor {
        #if canImport(UIKit)
        .tertiarySystemFill
        #else
        .quaternaryLabelColor.withAlphaComponent(0.18)
        #endif
    }
}

// MARK: - Images

extension PlatformImage {
    static func sm(cgImage: CGImage) -> PlatformImage {
        #if canImport(UIKit)
        UIImage(cgImage: cgImage)
        #else
        NSImage(cgImage: cgImage, size: .zero)
        #endif
    }
}

extension Image {
    init(platform image: PlatformImage) {
        #if canImport(UIKit)
        self.init(uiImage: image)
        #else
        self.init(nsImage: image)
        #endif
    }
}

// MARK: - Screen metrics

enum SMScreen {
    /// Main-screen width in points (used only for layout caps in markdown tables).
    static var mainWidth: CGFloat {
        #if canImport(UIKit)
        UIScreen.main.bounds.width
        #else
        NSScreen.main?.frame.width ?? 1280
        #endif
    }
}

// MARK: - Pasteboard

enum SMPasteboard {
    static var string: String? {
        #if canImport(UIKit)
        UIPasteboard.general.string
        #else
        NSPasteboard.general.string(forType: .string)
        #endif
    }
    static func set(_ s: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = s
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
        #endif
    }
    static var image: PlatformImage? {
        #if canImport(UIKit)
        UIPasteboard.general.image
        #else
        NSImage(pasteboard: NSPasteboard.general)
        #endif
    }
}

// MARK: - Haptics (no-op on the Mac)

enum Haptics {
    static func selection() {
        #if canImport(UIKit)
        UISelectionFeedbackGenerator().selectionChanged()
        #endif
    }
}

// MARK: - Keyboard kinds (UIKeyboardType is UIKit-only)

enum SMKeyboardKind {
    case asciiCapable, url, emailAddress, numberPad, plain
    #if canImport(UIKit)
    var uiKind: UIKeyboardType {
        switch self {
        case .asciiCapable: return .asciiCapable
        case .url: return .URL
        case .emailAddress: return .emailAddress
        case .numberPad: return .numberPad
        case .plain: return .default
        }
    }
    #endif
}

// MARK: - Presentation detents (iOS-only concept)

enum SMDetent {
    case medium, large
    case fraction(CGFloat)
    case height(CGFloat)
    #if os(iOS)
    var native: PresentationDetent {
        switch self {
        case .medium: return .medium
        case .large: return .large
        case .fraction(let f): return .fraction(f)
        case .height(let h): return .height(h)
        }
    }
    #endif
}

// MARK: - Toolbar placement

extension ToolbarItemPlacement {
    static var smTopTrailing: ToolbarItemPlacement {
        #if os(iOS)
        .topBarTrailing
        #else
        .automatic
        #endif
    }
    static var smTopLeading: ToolbarItemPlacement {
        #if os(iOS)
        .topBarLeading
        #else
        .navigation
        #endif
    }
}

// MARK: - View modifier shims (iOS-only modifiers become no-ops or mac analogs)

extension View {
    @ViewBuilder func smInlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
    @ViewBuilder func smLargeNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.large)
        #else
        self
        #endif
    }
    @ViewBuilder func smHideNavigationBar() -> some View {
        #if os(iOS)
        toolbar(.hidden, for: .navigationBar)
        #else
        self
        #endif
    }
    @ViewBuilder func smNoAutocapitalization() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never)
        #else
        self
        #endif
    }
    @ViewBuilder func smKeyboard(_ kind: SMKeyboardKind) -> some View {
        #if os(iOS)
        keyboardType(kind.uiKind)
        #else
        self
        #endif
    }
    @ViewBuilder func smPresentationDetents(_ detents: [SMDetent]) -> some View {
        #if os(iOS)
        presentationDetents(Set(detents.map(\.native)))
        #else
        self
        #endif
    }
    @ViewBuilder func smHoverHighlight() -> some View {
        #if os(iOS)
        hoverEffect(.highlight)
        #else
        self
        #endif
    }
    /// iOS full-screen cover; a regular sheet on the Mac (macOS has no full-screen cover).
    @ViewBuilder func smFullScreenCover<C: View>(
        isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> C
    ) -> some View {
        #if os(iOS)
        fullScreenCover(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #else
        sheet(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #endif
    }
    @ViewBuilder func smFullScreenCover<I: Identifiable, C: View>(
        item: Binding<I?>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (I) -> C
    ) -> some View {
        #if os(iOS)
        fullScreenCover(item: item, onDismiss: onDismiss, content: content)
        #else
        sheet(item: item, onDismiss: onDismiss, content: content)
        #endif
    }
}
```

- [ ] **Step 2: iOS build check.** Expected: `BUILD SUCCEEDED` (the file is purely additive; on iOS every shim resolves to today's exact behavior).

- [ ] **Step 3: Commit.**

```bash
git add apps/iosApp/Supermux/App/PlatformShims.swift
git commit -m "feat(mac): PlatformShims — cross-platform typealiases, representable protocol, modifier shims"
```

---

### Task 4: Mechanical sweep — replace iOS-only API calls with shims

This is a bulk find/replace across `apps/iosApp/Supermux/` guided by the table below. It does NOT touch the 8 view-wrapper structs, the terminal, display, dictation, or push code (Tasks 6-11 own those). After this task the iOS app behaves identically (every shim is a pass-through on iOS), and the mac error count drops massively.

**Files:** ~30 files under `apps/iosApp/Supermux/` (grep-driven; excludes `Watch/**`)

- [ ] **Step 1: Apply the replacement table.** For each row: `grep -rn '<old>' apps/iosApp/Supermux --include='*.swift'`, then Edit each hit (skip `Watch/` files — they're iOS-target-only):

| Old (iOS-only) | New (shim) |
|---|---|
| `.navigationBarTitleDisplayMode(.inline)` | `.smInlineNavigationTitle()` |
| `.navigationBarTitleDisplayMode(.large)` | `.smLargeNavigationTitle()` |
| `.toolbar(.hidden, for: .navigationBar)` | `.smHideNavigationBar()` |
| `ToolbarItem(placement: .topBarTrailing)` | `ToolbarItem(placement: .smTopTrailing)` |
| `ToolbarItem(placement: .topBarLeading)` | `ToolbarItem(placement: .smTopLeading)` |
| `.textInputAutocapitalization(.never)` | `.smNoAutocapitalization()` |
| `.keyboardType(.URL)` | `.smKeyboard(.url)` |
| `.keyboardType(.asciiCapable)` | `.smKeyboard(.asciiCapable)` |
| `.keyboardType(.emailAddress)` | `.smKeyboard(.emailAddress)` |
| `.keyboardType(.numberPad)` | `.smKeyboard(.numberPad)` |
| `.presentationDetents([` … `])` | `.smPresentationDetents([` … `])` — map `.medium`→`.medium`, `.large`→`.large`, `.fraction(x)`→`.fraction(x)`, `.height(x)`→`.height(x)` |
| `.fullScreenCover(` | `.smFullScreenCover(` |
| `.hoverEffect(.highlight)` | `.smHoverHighlight()` |
| `UISelectionFeedbackGenerator().selectionChanged()` | `Haptics.selection()` |
| `UIPasteboard.general.string = X` | `SMPasteboard.set(X)` |
| `UIPasteboard.general.string` (read) | `SMPasteboard.string` |
| `Image(uiImage:` | `Image(platform:` |
| `UIColor.label` / `.secondaryLabel` / `.tertiarySystemBackground` / `.tertiarySystemFill` | `PlatformColor.smLabel` / `.smSecondaryLabel` / `.smTertiaryBackground` / `.smTertiaryFill` |
| `UIColor(` (theme-color bridging) | `PlatformColor(` |
| `UIFont.` | `PlatformFont.` |
| `UIScreen.main.bounds.width` | `SMScreen.mainWidth` |

Stored haptic generators (e.g. `EditorSearchField.swift`'s `UISelectionFeedbackGenerator` property, and any `let … = UISelectionFeedbackGenerator()` in `EditorTabsView.swift` / `FileTreeView.swift` / `DiffView.swift`): delete the property and replace each `<prop>.selectionChanged()` call with `Haptics.selection()`.

- [ ] **Step 2: Conditionalize the now-unneeded `import UIKit` lines.** For every non-`Watch/` file that still has `import UIKit`: if the file has NO remaining `UI*` symbol references after Step 1 (check with `grep -n 'UI[A-Z]' <file>`), delete the import. If iOS-only blocks remain (they will in the files owned by Tasks 5-11 — leave those files' imports alone for now), keep the import but the owning task will wrap it.

- [ ] **Step 3: Verify the sweep is complete.** All of these must return zero hits outside `Watch/` and outside the Task 5-11 files (`TerminalHost.swift`, `TerminalPane.swift`, `SwiftTermView.swift`, `EditorWebView.swift`, `MarkdownView.swift`, `ComposerInput.swift`, `ComposerModel.swift`, `ChatView.swift`, `ChatPane.swift`, `ChatMessages.swift`, `DisplayHost.swift`, `DisplayPane.swift`, `VncMetalView.swift`, `ScrcpyVideoView.swift`, `SpeechDictation.swift`, `AudioRecorder.swift`, `PushManager.swift`, `InfoPages.swift`):

```bash
grep -rn 'navigationBarTitleDisplayMode\|topBarTrailing\|topBarLeading\|textInputAutocapitalization\|keyboardType(\|presentationDetents(\|fullScreenCover(\|hoverEffect(\|UISelectionFeedbackGenerator' apps/iosApp/Supermux --include='*.swift' | grep -v 'Watch/' | grep -v 'PlatformShims'
```

- [ ] **Step 4: iOS build check.** Expected: `BUILD SUCCEEDED`. Behavior must be identical — shims are pass-throughs on iOS.

- [ ] **Step 5: Commit.**

```bash
git add -A apps/iosApp/Supermux
git commit -m "refactor(ios): route iOS-only SwiftUI/UIKit API calls through PlatformShims"
```

---

### Task 5: App entry + adaptive-layout gates

**Files:**
- Modify: `apps/iosApp/Supermux/App/SupermuxApp.swift`
- Modify: `apps/iosApp/Supermux/Shell/RootView.swift`
- Modify: `apps/iosApp/Supermux/Editor/EditorPane.swift:18` (same pattern)
- Modify: `apps/iosApp/Supermux/Sessions/InfoPages.swift` (pasteboard + QR image, if not fully swept in Task 4)

- [ ] **Step 1: SupermuxApp.swift — delegate adaptor + watch guard.** Replace lines 9 and 27-29, and the two `PhoneWatchProvisioner` call sites in `body` (lines 44 and 56), so the file's changed regions read:

```swift
    // UIKit/AppKit AppDelegate (push/APNs) adapted into the SwiftUI lifecycle.
    #if os(iOS)
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate
    #else
    @NSApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate
    #endif
```

In `init()` (was lines 27-29):

```swift
        #if os(iOS)
        // Start the WatchConnectivity channel so a paired Apple Watch gets the broker
        // credentials (pushed on activation + whenever they change below).
        PhoneWatchProvisioner.shared.activate()
        #endif
```

In `body`, both `PhoneWatchProvisioner.shared.pushCurrent()` calls (pairing callback + onOpenURL):

```swift
                        #if os(iOS)
                        PhoneWatchProvisioner.shared.pushCurrent()
                        #endif
```

Also add a default window size for the Mac — after the `WindowGroup { … }` closing brace but inside `body`, chain:

```swift
        #if os(macOS)
        .defaultSize(width: 1440, height: 900)
        #endif
```

(`PushAppDelegate` itself becomes cross-platform in Task 11 — until then the mac build still errors in `PushManager.swift`; that's expected.)

- [ ] **Step 2: RootView.swift — width-class gate.** Replace line 14 (`@Environment(\.horizontalSizeClass) private var hSize`) with:

```swift
    #if os(macOS)
    private let isRegularWidth = true   // the Mac is always the wide multi-pane workspace
    #else
    @Environment(\.horizontalSizeClass) private var hSize
    private var isRegularWidth: Bool { hSize == .regular }
    #endif
```

Then replace the three usages: line 34 `if hSize == .regular { regularShell } else { compactShell }` → `if isRegularWidth { regularShell } else { compactShell }`; line 45 `guard hSize == .regular else { return }` → `guard isRegularWidth else { return }`. (Line 91's `.fullScreenCover(item:)` became `.smFullScreenCover(item:)` in Task 4.)

- [ ] **Step 3: EditorPane.swift — same pattern.** Line 18 has the second `horizontalSizeClass` usage. Apply the identical `#if os(macOS)` property split (`isRegularWidth`), and change its `hSize == .regular` comparisons to `isRegularWidth`.

- [ ] **Step 4: InfoPages.swift — QR + pasteboard.** Line 193's pasteboard read was swept in Task 4. Replace line 202-208's `UIImage(cgImage:)` with `PlatformImage.sm(cgImage:)` and any `Image(uiImage:)` with `Image(platform:)`; then delete the `import UIKit` (CoreImage stays — it's cross-platform).

- [ ] **Step 5: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 6: Commit.**

```bash
git add apps/iosApp/Supermux/App/SupermuxApp.swift apps/iosApp/Supermux/Shell/RootView.swift apps/iosApp/Supermux/Editor/EditorPane.swift apps/iosApp/Supermux/Sessions/InfoPages.swift
git commit -m "feat(mac): cross-platform app entry, always-regular workspace layout on macOS"
```

---

### Task 6: Terminal subsystem

**Files:**
- Modify: `apps/iosApp/Supermux/Terminal/SwiftTermView.swift` (full replacement below)
- Modify: `apps/iosApp/Supermux/Terminal/TerminalHost.swift`
- Modify: `apps/iosApp/Supermux/Terminal/TerminalPane.swift:39-42,65`

- [ ] **Step 1: SwiftTermView → PlatformViewRepresentable.** Replace the whole struct (keep the doc comment):

```swift
struct SwiftTermView: PlatformViewRepresentable {
    let view: TerminalView

    func makePlatformView(context: Context) -> TerminalView {
        // Detach from any prior mount before SwiftUI re-parents this cached, reused view.
        view.removeFromSuperview()
        return view
    }

    func updatePlatformView(_ view: TerminalView, context: Context) {}
}
```

(SwiftTerm's `TerminalView` is a UIView on iOS and an NSView on macOS under the same type name — the protocol handles both.)

- [ ] **Step 2: TerminalHost.swift — substitutions + iOS-only regions.**
  1. Change `import UIKit` (line 5) to `#if canImport(UIKit)\nimport UIKit\n#endif`.
  2. Font/colors (lines 23-25): `UIFont.monospacedSystemFont` → `PlatformFont.monospacedSystemFont` and `UIColor(…)` → `PlatformColor(…)` (both exist on NSFont/NSColor with the same signatures).
  3. Wrap the ENTIRE soft-keyboard + touch-scroll machinery in `#if os(iOS)` … `#endif`. That is: the `inputAccessoryView`/`inputView`/`reloadInputViews` block (~line 101 and 185-195), the `GCKeyboard` connect/disconnect observer setup (~84-100), `installTouchScroll()` and its `UIPanGestureRecognizer` handler (~124-161), the `UIGestureRecognizerDelegate` conformance + `gestureRecognizer(_:shouldBeRequiredToFailBy:)` (~166-173), and every call site of those members. If the class declares `UIGestureRecognizerDelegate` conformance in its declaration line, move that conformance to an `#if os(iOS)` extension:

```swift
#if os(iOS)
extension TerminalHost: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        // (existing body, moved verbatim)
    }
}
#endif
```

  On macOS nothing replaces any of it: hardware keyboards are always present (no accessory/input views), and SwiftTerm's AppKit view converts `scrollWheel` events into SGR mouse-wheel reports itself when the app (tmux `mouse on`) requests mouse mode — the drag→wheel-bytes bridge is an iOS-touch workaround, not a feature to port.
  4. `PredictionAdapter.swift` needs NO change (SwiftTerm-API only, no UIKit import).

- [ ] **Step 3: TerminalPane.swift — keyboard insets.** Wrap the `UIResponder.keyboardWillShow/HideNotification` inset logic (lines 39-42) and the `resignFirstResponder` call (line 65) in `#if os(iOS)`; change `import UIKit` to the `canImport` form (or delete if nothing UIKit-flavored remains outside the wrapped block).

- [ ] **Step 4: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 5: Commit.**

```bash
git add apps/iosApp/Supermux/Terminal
git commit -m "feat(mac): terminal — AppKit SwiftTerm view, iOS-only soft-keyboard/touch-scroll guards"
```

---

### Task 7: Editor subsystem

**Files:**
- Modify: `apps/iosApp/Supermux/Editor/EditorWebView.swift:18`

- [ ] **Step 1: EditorWebView → PlatformViewRepresentable.** Change `struct EditorWebView: UIViewRepresentable` to `struct EditorWebView: PlatformViewRepresentable`, rename `makeUIView(context:)` → `makePlatformView(context:)` and `updateUIView(_:context:)` → `updatePlatformView(_:context:)` keeping the bodies verbatim (WKWebView has identical API on macOS). If the file imports UIKit, apply the `canImport` conditional or delete the import (`import WebKit` stays).

- [ ] **Step 2: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 3: Commit.**

```bash
git add apps/iosApp/Supermux/Editor
git commit -m "feat(mac): editor WKWebView representable goes cross-platform"
```

---

### Task 8: Chat subsystem

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/MarkdownView.swift`
- Modify: `apps/iosApp/Supermux/Chat/Composer/ComposerInput.swift`
- Modify: `apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift`
- Modify: `apps/iosApp/Supermux/Chat/ChatView.swift:47-50`
- Modify: `apps/iosApp/Supermux/Chat/ChatPane.swift` (pasteboard swept in Task 4; import cleanup only)
- Modify: `apps/iosApp/Supermux/Chat/ChatMessages.swift`

- [ ] **Step 1: MarkdownView.swift.** Apply the Task 4 substitutions inside this file (it was deferred to here): `UIFont.`→`PlatformFont.`, `UIColor.label`→`PlatformColor.smLabel`, `.secondaryLabel`→`.smSecondaryLabel`, `.tertiarySystemBackground`→`.smTertiaryBackground`, `.tertiarySystemFill`→`.smTertiaryFill`, other `UIColor(`→`PlatformColor(`, `UIScreen.main.bounds.width`→`SMScreen.mainWidth` (lines 555-556). Then replace `SelectableText` (lines 524-575) with a cross-platform pair — keep the iOS `UITextView` implementation inside `#if canImport(UIKit)` verbatim, and add the mac twin:

```swift
#if canImport(UIKit)
// (existing SelectableText: UIViewRepresentable implementation, unchanged, moved inside this block)
#else
/// Selectable rich-text block for the Mac: a non-editable, selectable NSTextView
/// that hugs its content height (mirrors the iOS UITextView configuration).
struct SelectableText: NSViewRepresentable {
    let attributed: NSAttributedString

    func makeNSView(context: Context) -> NSTextView {
        let tv = NSTextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.drawsBackground = false
        tv.textContainerInset = .zero
        tv.textContainer?.lineFragmentPadding = 0
        tv.textContainer?.widthTracksTextView = true
        tv.isVerticallyResizable = false
        tv.isHorizontallyResizable = false
        return tv
    }

    func updateNSView(_ tv: NSTextView, context: Context) {
        tv.textStorage?.setAttributedString(attributed)
    }
}
#endif
```

**Adapt the property list/names of the mac twin to EXACTLY the iOS struct's stored properties and init** (read the existing struct first — if it takes more than the attributed string, e.g. width or link-handler closures, mirror each one; the NSTextView link handling analog is `tv.linkTextAttributes` + the default NSTextView link behavior, which opens URLs — for the in-app file-path links keep the same URL-scheme routing the iOS side uses, wired via `NSTextView.delegate` (`textView(_:clickedOnLink:at:)`) calling the same closure the iOS coordinator calls). Finally change `import UIKit` to the `canImport` conditional.

- [ ] **Step 2: ComposerInput.swift.** Keep the whole existing `UITextView`-based implementation inside `#if canImport(UIKit)` and add a mac twin in the `#else` branch. The iOS wrapper exists to (a) grow with content, (b) intercept paste for images/files, (c) forward first-responder control. Mac twin:

```swift
#else
import AppKit

/// Mac composer input: NSTextView that grows with content and intercepts paste
/// so image/file pastes route to attachments exactly like the iOS PasteTextView.
struct ComposerInput: NSViewRepresentable {
    @Binding var text: String
    var onPasteImage: (PlatformImage) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSTextView.scrollableTextView()
        let tv = scroll.documentView as! PasteTextView
        // scrollableTextView() makes a plain NSTextView; swap in our subclass:
        let paste = PasteTextView(frame: tv.frame, textContainer: tv.textContainer)
        paste.onPasteImage = { context.coordinator.parent.onPasteImage($0) }
        paste.delegate = context.coordinator
        paste.font = .systemFont(ofSize: NSFont.systemFontSize)
        paste.isRichText = false
        paste.allowsUndo = true
        paste.drawsBackground = false
        scroll.documentView = paste
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = false
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        guard let tv = scroll.documentView as? PasteTextView else { return }
        if tv.string != text { tv.string = text }
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ComposerInput
        init(_ parent: ComposerInput) { self.parent = parent }
        func textDidChange(_ notification: Notification) {
            guard let tv = notification.object as? NSTextView else { return }
            parent.text = tv.string
        }
    }
}

final class PasteTextView: NSTextView {
    var onPasteImage: ((PlatformImage) -> Void)?

    override func paste(_ sender: Any?) {
        if let img = NSImage(pasteboard: .general) {
            onPasteImage?(img)
            return
        }
        super.paste(sender)
    }
}
#endif
```

**Before writing the twin, read the iOS `ComposerInput` struct's exact public interface** (bindings, callbacks, height reporting) and mirror it 1:1 — the SwiftUI call sites must compile unchanged on both platforms. If the iOS version reports content height via a binding or preference, implement the same on macOS with `layoutManager.usedRect(for:)` in `textDidChange`.

- [ ] **Step 3: ComposerModel.swift.** `UIImage` (lines 110, 134) → `PlatformImage`; `UIPasteboard` image/data reads (lines 122-133) → `SMPasteboard.image` (and if the iOS code also reads pasteboard `data(forPasteboardType:)` for non-image files, wrap that block in `#if canImport(UIKit)` and add the mac analog via `NSPasteboard.general.readObjects(forClasses: [NSURL.self])` for file URLs). `PlatformImage.jpegData` does not exist on NSImage — add this helper to the BOTTOM of `PlatformShims.swift`:

```swift
extension PlatformImage {
    func smJpegData(quality: CGFloat) -> Data? {
        #if canImport(UIKit)
        jpegData(compressionQuality: quality)
        #else
        guard let tiff = tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .jpeg, properties: [.compressionFactor: quality])
        #endif
    }
}
```

and change call sites `X.jpegData(compressionQuality: q)` → `X.smJpegData(quality: q)` (grep the whole `Supermux/` tree, excluding `Watch/`).

- [ ] **Step 4: ChatView.swift logo rasterizer (lines 47-50).** Wrap the `UIImage(named:)` + `UIGraphicsImageRenderer` block:

```swift
        #if canImport(UIKit)
        // (existing UIGraphicsImageRenderer code, verbatim)
        #else
        if let base = NSImage(named: "AgentLogo") {   // ← keep the EXISTING asset name from the current code
            let img = NSImage(size: targetSize, flipped: false) { rect in
                base.draw(in: rect)
                return true
            }
            // …assign to the same variable the iOS branch assigns…
        }
        #endif
```

Use the actual asset name / target-size variable already present in the code; only the rendering API changes.

- [ ] **Step 5: ChatMessages.swift.** `UIImage` (lines 40-56) → `PlatformImage`. Wrap `CameraPicker` (lines 159-181) and every view that presents it in `#if os(iOS)` — the Mac simply doesn't offer camera capture (the photo-library `PhotosPicker` is cross-platform and stays). For the QuickLook preview: if it uses a `QLPreviewController` representable, wrap that in `#if os(iOS)` and on macOS use the SwiftUI modifier instead at the same call site:

```swift
        #if os(macOS)
        .quickLookPreview($previewURL)   // bind to the same URL state the iOS path uses
        #endif
```

- [ ] **Step 6: ChatPane.swift.** Pasteboard calls were swept in Task 4; now delete/conditionalize its `import UIKit` (PhotosUI import stays — cross-platform).

- [ ] **Step 7: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 8: Commit.**

```bash
git add apps/iosApp/Supermux/Chat apps/iosApp/Supermux/App/PlatformShims.swift
git commit -m "feat(mac): chat — NSTextView markdown/composer twins, mac pasteboard + image paths"
```

---

### Task 9: Display subsystem (VNC + scrcpy)

**Files:**
- Modify: `apps/iosApp/Supermux/Display/VncMetalView.swift`
- Modify: `apps/iosApp/Supermux/Display/ScrcpyVideoView.swift`
- Modify: `apps/iosApp/Supermux/Display/DisplayPane.swift` (3 representables + key capture)
- Modify: `apps/iosApp/Supermux/Display/DisplayHost.swift` (backing view typealias)

- [ ] **Step 1: DisplayHost.swift.** Replace `UIView` with `PlatformView` and conditionalize the UIKit import. (`PlatformView` from Task 3 = `UIView`/`NSView`.)

- [ ] **Step 2: VncMetalView.swift.** The class is a `UIView` whose `layerClass` is `CAMetalLayer` (lines 23-25) with scale from `traitCollection.displayScale`/`windowScene` (32-33). Wrap the class-level platform differences:

```swift
#if canImport(UIKit)
// existing: override class var layerClass: AnyClass { CAMetalLayer.self }
// existing scale code (traitCollection.displayScale / windowScene)
#else
// macOS: NSView has no layerClass — make the view layer-hosting at init:
//   wantsLayer = true
//   layer = CAMetalLayer()
// and read scale from the window:
//   let scale = window?.backingScaleFactor ?? (NSScreen.main?.backingScaleFactor ?? 2)
// Re-apply the scale in viewDidMoveToWindow() (the mac analog of didMoveToWindow).
#endif
```

Concretely: change the superclass reference `UIView` → `PlatformView`; put the `layerClass` override inside `#if canImport(UIKit)`; add to the mac side an `override func viewDidMoveToWindow()` calling the same scale-update routine the iOS `didMoveToWindow`/trait path calls, and set `wantsLayer = true; layer = CAMetalLayer()` in the initializer's `#else` branch. Everything Metal (device, pipeline, draw) is identical on macOS — do not touch it.

- [ ] **Step 3: ScrcpyVideoView.swift.** Same recipe as Step 2 with `AVSampleBufferDisplayLayer` instead of `CAMetalLayer` (`AVSampleBufferDisplayLayer` and VideoToolbox are fully supported on macOS).

- [ ] **Step 4: DisplayPane.swift.** Convert `VncSurfaceView` (line 92) and `ScrcpySurfaceView` (line 108) to `PlatformViewRepresentable` (rename make/update methods, bodies unchanged). For `DisplayKeyboardField` (line 417) keep the iOS `UITextField`-based implementation under `#if canImport(UIKit)` and add the mac twin:

```swift
#else
/// Invisible key-capture surface for the Mac: forwards keyDown/deletes to the
/// same callbacks the iOS KeyCaptureField drives.
private struct DisplayKeyboardField: NSViewRepresentable {
    var onText: (String) -> Void
    var onBackspace: () -> Void
    var onReturn: () -> Void

    func makeNSView(context: Context) -> KeyCaptureView {
        let v = KeyCaptureView()
        v.onText = onText; v.onBackspace = onBackspace; v.onReturn = onReturn
        return v
    }
    func updateNSView(_ v: KeyCaptureView, context: Context) {}
}

final class KeyCaptureView: NSView {
    var onText: ((String) -> Void)?
    var onBackspace: (() -> Void)?
    var onReturn: (() -> Void)?

    override var acceptsFirstResponder: Bool { true }

    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 51: onBackspace?()          // delete
        case 36, 76: onReturn?()         // return / keypad-enter
        default:
            if let s = event.characters, !s.isEmpty { onText?(s) }
        }
    }
}
#endif
```

**Mirror the EXACT callback names/signatures of the existing iOS `DisplayKeyboardField`** (read it first — if it uses a single `onKey(String)`-style closure or a focus binding, replicate that; the snippet above shows the shape, the real property list comes from the iOS struct). Wire focus: where the iOS side makes the field first responder, on macOS call `view.window?.makeFirstResponder(view)`.

- [ ] **Step 5: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 6: Commit.**

```bash
git add apps/iosApp/Supermux/Display
git commit -m "feat(mac): display pane — Metal/VNC + scrcpy views and key capture on AppKit"
```

---

### Task 10: Dictation + audio recording

**Files:**
- Modify: `apps/iosApp/Supermux/Chat/SpeechDictation.swift`
- Modify: `apps/iosApp/Supermux/Chat/AudioRecorder.swift`

- [ ] **Step 1: SpeechDictation.swift — kill the AVAudioSession dance on macOS.** Wrap the bodies of `configureAudioSession()` (lines 283-291) and `deactivateSession()` (302-304):

```swift
    private func configureAudioSession() throws {
        #if os(iOS)
        // (existing AVAudioSession.sharedInstance() code, verbatim)
        #endif
        // macOS: no audio session — AVAudioEngine drives the mic directly.
    }
```

(same shape for `deactivateSession()`). The engine/tap code (lines 65, 202, 251) is cross-platform — untouched.

- [ ] **Step 2: Availability annotations.** The new-stack gates are written `iOS 26.0`-only. At each of lines 86, 108, 132, 142, 156, 336, 571: `@available(iOS 26.0, *)` → `@available(iOS 26.0, macOS 26.0, *)` and `#available(iOS 26.0, *)` → `#available(iOS 26.0, macOS 26.0, *)`. (`SpeechAnalyzer`/`SpeechTranscriber` exist on macOS 26; `SFSpeechRecognizer.requestAuthorization` and `AVAudioApplication.requestRecordPermission` are cross-platform.)

- [ ] **Step 3: AudioRecorder.swift.** Wrap the `AVAudioSession.sharedInstance()` blocks (lines 21, 67) in `#if os(iOS)` exactly like Step 1 (`AVAudioRecorder` itself works session-less on macOS); the `AVAudioApplication.requestRecordPermission` call (line 82) stays — it exists on macOS 14+.

- [ ] **Step 4: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 5: Commit.**

```bash
git add apps/iosApp/Supermux/Chat/SpeechDictation.swift apps/iosApp/Supermux/Chat/AudioRecorder.swift
git commit -m "feat(mac): dictation + voice notes — session-less mic path, macOS 26 availability"
```

---

### Task 11: Push notifications

**Files:**
- Modify: `apps/iosApp/Supermux/Push/PushManager.swift`
- Verify-only: `apps/iosApp/SupermuxPushNSE/NotificationService.swift`, `Supermux/Push/PushCrypto.swift`, `Supermux/Push/PushKeypair.swift` (should be UIKit-free; the mac NSE target from Task 2 compiles them)

- [ ] **Step 1: PushManager.swift — cross-platform registration + delegate.** Change `import UIKit` (line 32) to:

```swift
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
```

Line 65 `UIApplication.shared.registerForRemoteNotifications()`:

```swift
        #if canImport(UIKit)
        UIApplication.shared.registerForRemoteNotifications()
        #else
        NSApplication.shared.registerForRemoteNotifications()
        #endif
```

The delegate class (line 154) splits at the conformance + the one iOS-only method signature. Keep every method body identical; only the protocol and the background-fetch signature differ:

```swift
#if canImport(UIKit)
final class PushAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    // (existing implementation, verbatim, including
    //  application(_:didFinishLaunchingWithOptions:),
    //  didRegisterForRemoteNotificationsWithDeviceToken, didFailToRegister…,
    //  didReceiveRemoteNotification…fetchCompletionHandler, and the UNUserNotificationCenter methods)
}
#else
final class PushAppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // same body as the iOS didFinishLaunching path: set UNUserNotificationCenter
        // delegate + PushManager.shared.registerIfPaired()
    }
    func application(_ application: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        // same body as iOS
    }
    func application(_ application: NSApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // same body as iOS
    }
    func application(_ application: NSApplication, didReceiveRemoteNotification userInfo: [String: Any]) {
        // same body as the iOS didReceiveRemoteNotification, minus the completionHandler call
    }
    // UNUserNotificationCenterDelegate methods: identical bodies, copied verbatim
}
#endif
```

Move the shared method bodies rather than duplicating logic where possible (e.g. hoist the token-upload + tap-routing bodies into private funcs both branches call — `private func handleToken(_ data: Data)`, `private func handleRemote(_ userInfo: [AnyHashable: Any])`, `private func handleTap(_ response: UNNotificationResponse)` — so the `#if` branches are thin shells).

- [ ] **Step 2: Platform tag decision (documented in code).** The registration payload keeps `platform: "ios"` on macOS too — the relay routes by platform string to APNs vs FCM, and the mac app shares the iOS bundle id (`dev.supermux.app`), so the same APNs topic works end-to-end with ZERO broker changes (a spec decision). Add this comment at the `platform:` argument in `PushManager.swift` (near lines 39/94):

```swift
            // macOS also registers as "ios": same APNs topic (shared bundle id), and
            // the relay only distinguishes APNs vs FCM. Introduce "macos" only when
            // the broker learns to segment device platforms.
```

- [ ] **Step 3: Verify NSE sources are UIKit-free** (they must compile in the mac NSE target from Task 2):

```bash
grep -n 'import UIKit\|UIApplication' apps/iosApp/SupermuxPushNSE/NotificationService.swift apps/iosApp/Supermux/Push/PushCrypto.swift apps/iosApp/Supermux/Push/PushKeypair.swift
```

Expected: no hits. If `PushKeypair`/`PushCrypto` picked up any, fix with the same `canImport` pattern.

- [ ] **Step 4: iOS build check.** Expected: `BUILD SUCCEEDED`.

- [ ] **Step 5: Commit.**

```bash
git add apps/iosApp/Supermux/Push apps/iosApp/SupermuxPushNSE
git commit -m "feat(mac): APNs registration + NSApplicationDelegate push path, mac NSE"
```

---

### Task 12: GREEN MAC BUILD — burn down the residual audit list

Tasks 3-11 covered every UIKit category the source audit found. Whatever still fails now is stragglers of the SAME categories (a missed modifier instance, an unconditioned import, a file the audit's grep missed).

- [ ] **Step 1: Build loop.** Tar-sync, run the Mac build check, and iterate:
  1. Take the FIRST `error:` line in `mac-build.log`.
  2. Classify it against the Task 4 table / Task 5-11 patterns and apply the matching fix (same shim, same `#if` wrap — no new inventions; if something genuinely novel appears, e.g. an API with no mac analog, wrap it `#if os(iOS)` and file its mac behavior as a follow-up noted in the commit message).
  3. Rebuild. Repeat until `BUILD SUCCEEDED`.

Track progress against the baseline: `grep -cE 'error:' ~/supermux-mac/mac-build.log` must strictly decrease each iteration; if it doesn't, stop and re-read the error — you're fixing the wrong thing.

- [ ] **Step 2: Both platforms green.** Run the iOS build check AND the Mac build check. Expected: both `BUILD SUCCEEDED`. **From this task on, every later task must keep BOTH green.**

- [ ] **Step 3: First launch smoke.** Build ad-hoc-signed and launch on the Mac against the live broker (mint a token on this host: `cd /home/ahmet/projects/supermux && bun run pair mac-dev` → take the `?t=` value):

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && \
  xcodebuild -scheme SupermuxMac -destination "platform=macOS,arch=arm64" -derivedDataPath build/dd-mac \
  CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=YES CODE_SIGNING_ALLOWED=YES build && \
  ( SM_PAIR_TOKEN="<token>" SM_PAIR_BASE="http://100.84.92.82:9898" \
    build/dd-mac/Build/Products/Debug/Supermux.app/Contents/MacOS/Supermux \
    > ~/supermux-mac/app.log 2>&1 & ) && sleep 20 && pgrep -x Supermux'
```

Expected: a PID is printed (the app is running, not crashed at launch). APNs registration failing under ad-hoc signing is EXPECTED — ignore that log line in `app.log`.

- [ ] **Step 4: Commit** whatever stragglers Step 1 touched:

```bash
git add -A apps/iosApp
git commit -m "feat(mac): green macOS build — residual UIKit stragglers conditioned"
```

---

### Task 13: "Not responding" dead-session view (shared — closes the iOS gap too)

The broker ships `state: "idle" | "working" | "dead"` in `agent_state` frames (`apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt:140`). Web + Android render a dead-state treatment; iOS/macOS don't. Build it once in the shared SwiftUI.

**Files:**
- Modify: `apps/iosApp/Supermux/Broker/BrokerSession.swift:88,131-135`
- Create: `apps/iosApp/Supermux/Sessions/DeadSessionBanner.swift`
- Modify: `apps/iosApp/Supermux/Chat/ChatView.swift` (attach banner)
- Test: `apps/iosApp/SupermuxTests/` (extend whichever existing test file covers BrokerSession frame handling; if none does, add `AgentDeadStateTests.swift`)

- [ ] **Step 1: Track dead state in BrokerSession.** Next to the existing `agentWorking` declaration add `var agentDead: [String: Bool] = [:]` (match the surrounding property style — `@Observable` class). In the snapshot handler (line 88 area) add, mirroring the `agentWorking` line:

```swift
            agentDead = s.agentState.mapValues { $0.state == "dead" }
```

In the live-frame handler (lines 131-135 area) add:

```swift
            agentDead[st.session] = st.state == "dead"
```

- [ ] **Step 2: Write the failing test.** In `SupermuxTests`, drive `BrokerSession`'s frame handler the same way the existing state tests do (mirror the style of the nearest existing test that feeds frames/snapshots — grep for `agentWorking` in `SupermuxTests/` and extend that file if it exists). The assertion:

```swift
    func testDeadStateTracked() {
        // feed an agent_state frame/snapshot with state == "dead" for session "s1"
        // (constructed exactly like the existing agentWorking tests construct theirs)
        XCTAssertEqual(broker.agentDead["s1"], true)
        // then a state == "idle" frame clears it
        XCTAssertEqual(broker.agentDead["s1"], false)
    }
```

- [ ] **Step 3: Run tests on the remote Mac** (iOS test lane — mac test lane arrives in Task 15):

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && \
  nohup xcodebuild test -scheme Supermux -sdk iphonesimulator26.5 \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath build/dd-ios \
  > ~/supermux-mac/ios-test.log 2>&1 &'
# poll for "** TEST SUCCEEDED **"
```

Expected: the new test passes, all 104+ existing tests stay green.

- [ ] **Step 4: The banner view.** Create `apps/iosApp/Supermux/Sessions/DeadSessionBanner.swift`:

```swift
import SwiftUI

/// "Not responding" treatment for a dead agent (broker agent_state == "dead").
/// Parity with the web + Android dead-session banners.
struct DeadSessionBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("Not responding").font(.callout.weight(.semibold))
                Text("The agent process looks dead. Interrupt it or start a new session.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 10)
        .padding(.top, 6)
    }
}
```

- [ ] **Step 5: Attach it in ChatView.** At the top of `ChatView`'s body's outermost container (read the file; attach to the root view of `body`), add:

```swift
        .safeAreaInset(edge: .top, spacing: 0) {
            if broker.agentDead[session.id] == true {
                DeadSessionBanner()
            }
        }
```

(`broker` and `session` are ChatView's existing properties — `ChatView(broker:session:)` per `RootView.swift:129`.)

- [ ] **Step 6: Both build checks + iOS tests green.** Run the iOS build check, Mac build check, and the iOS test lane. Expected: all green.

- [ ] **Step 7: Commit.**

```bash
git add apps/iosApp/Supermux/Broker/BrokerSession.swift apps/iosApp/Supermux/Sessions/DeadSessionBanner.swift apps/iosApp/Supermux/Chat/ChatView.swift apps/iosApp/SupermuxTests
git commit -m "feat: Not-responding banner for dead sessions (iOS + macOS, closes the iOS gap)"
```

---

### Task 14: Mac chrome — menu commands + open-session-in-new-window

**Files:**
- Modify: `apps/iosApp/Supermux/App/SupermuxApp.swift`
- Create: `apps/iosApp/Supermux/Shell/SessionWindow.swift`
- Modify: `apps/iosApp/Supermux/Sessions/SessionsListView.swift` (context menu on rows)

- [ ] **Step 1: Menu commands + secondary window scene.** In `SupermuxApp.body`, after the main `WindowGroup`'s `.defaultSize` (Task 5), add the mac-only scenes/commands:

```swift
        #if os(macOS)
        WindowGroup(id: "session", for: String.self) { $sessionId in
            if let sessionId, let base = BrokerConfig.baseURL, let token = BrokerConfig.token {
                SessionWindow(baseURL: base, token: token, sessionId: sessionId)
            }
        }
        .defaultSize(width: 1000, height: 760)
        #endif
```

and on the MAIN `WindowGroup`, before `.defaultSize`:

```swift
        #if os(macOS)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("New Session") {
                    NotificationCenter.default.post(name: .smNewSession, object: nil)
                }
                .keyboardShortcut("n", modifiers: .command)
            }
            SidebarCommands()
            TextEditingCommands()
        }
        #endif
```

Add the notification name + RootView hookup: in `PlatformShims.swift` bottom:

```swift
extension Notification.Name {
    static let smNewSession = Notification.Name("sm.newSession")
}
```

and in `RootView.body` (chain after the existing `.onReceive(PushRouter…)`):

```swift
        .onReceive(NotificationCenter.default.publisher(for: .smNewSession)) { _ in
            route = .newSession
        }
```

- [ ] **Step 2: SessionWindow.** Create `apps/iosApp/Supermux/Shell/SessionWindow.swift` — a standalone window hosting one session's chat with its OWN BrokerSession (own WS, exactly like a second web tab):

```swift
import SwiftUI
import Shared

#if os(macOS)
/// A detached macOS window showing a single session's chat. Owns its own
/// BrokerSession (its own WS connection) — same model as a second web tab.
struct SessionWindow: View {
    @State private var broker: BrokerSession
    let sessionId: String

    init(baseURL: String, token: String, sessionId: String) {
        _broker = State(initialValue: BrokerSession(baseURL: baseURL, token: token))
        self.sessionId = sessionId
    }

    private var session: SessionInfo? {
        broker.sessions.first(where: { $0.id == sessionId })
    }

    var body: some View {
        Group {
            if let s = session {
                ChatView(broker: broker, session: s)
                    .navigationTitle(s.name)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .task { broker.start() }
        .frame(minWidth: 640, minHeight: 480)
    }
}
#endif
```

- [ ] **Step 3: "Open in New Window" on session rows.** In `SessionsListView.swift`, on the row view (the one that renders `SessionStatusRail` around line 183 — the per-row container), add:

```swift
        #if os(macOS)
        .contextMenu {
            Button("Open in New Window") {
                openWindow(id: "session", value: session.id)
            }
        }
        #endif
```

and give the row's view struct the environment action (top of the struct):

```swift
        #if os(macOS)
        @Environment(\.openWindow) private var openWindow
        #endif
```

- [ ] **Step 4: Sleep/wake reconnect (spec: error handling).** Open `apps/iosApp/Supermux/Broker/BrokerSession.swift` and locate the reconnect/backoff loop (grep `reconnect` / `retry` / the `start()` connection loop). Add a mac-only wake observer that forces an immediate retry instead of waiting out the current backoff delay — wire it where the connection loop is set up (inside `start()` or the class's init):

```swift
        #if os(macOS)
        // Macs sleep with the lid: on wake, don't sit out the backoff timer —
        // kick the connection loop immediately so the workspace is live on lid-open.
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.wakeKick()
        }
        #endif
```

`wakeKick()` is a new small method on `BrokerSession` that triggers the SAME code path the backoff timer's expiry triggers (call the existing retry entry point; if the loop sleeps via `Task.sleep`, keep a reference to that `Task` and cancel it — cancellation falls through to the retry iteration). Also add `import AppKit` under `#if os(macOS)` at the top of the file if not already importable. Verify by inspection that the retry path is idempotent when already connected (it checks connection state before dialing — if it doesn't, guard `wakeKick()` on the not-connected state).

- [ ] **Step 5: Both build checks green.** iOS + Mac. Expected: `BUILD SUCCEEDED` twice (all additions are `#if os(macOS)`-gated, so iOS is bit-identical).

- [ ] **Step 6: Commit.**

```bash
git add apps/iosApp/Supermux/App apps/iosApp/Supermux/Shell/SessionWindow.swift apps/iosApp/Supermux/Sessions/SessionsListView.swift apps/iosApp/Supermux/Broker/BrokerSession.swift
git commit -m "feat(mac): menu-bar commands, ⌘N, open-in-new-window, wake-kick reconnect"
```

---

### Task 15: Unit tests on the Mac (`SupermuxMacTests`)

**Files:**
- Modify: `apps/iosApp/project.yml`

- [ ] **Step 1: Find iOS-only test files.** The mac target excludes `Watch/**` sources, so tests touching them can't link:

```bash
grep -rln 'RelayEnvelope\|BrokerTransport\|WatchSessionStatus\|PhoneWatchProvisioner\|BrokerRelay' apps/iosApp/SupermuxTests
```

Note the list (expected: at least `WatchSessionStatusTests.swift`).

- [ ] **Step 2: Add the test target + scheme wiring** in `project.yml`. Target (after `SupermuxTests`):

```yaml
  SupermuxMacTests:
    type: bundle.unit-test
    platform: macOS
    sources:
      - path: SupermuxTests
        excludes:
          - "WatchSessionStatusTests.swift"   # + every file Step 1 found
    dependencies:
      - target: SupermuxMac
    settings:
      base:
        GENERATE_INFOPLIST_FILE: "YES"
        MACOSX_DEPLOYMENT_TARGET: "26.0"
        FRAMEWORK_SEARCH_PATHS:
          - $(inherited)
          - $(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
```

Scheme — extend the `SupermuxMac` scheme (Task 2 Step 5) to:

```yaml
  SupermuxMac:
    build:
      targets:
        SupermuxMac: all
        SupermuxMacTests: [test]
    test:
      config: Debug
      targets:
        - SupermuxMacTests
    run:
      config: Debug
    archive:
      config: Release
```

- [ ] **Step 3: Run the mac test suite.** Tar-sync, then:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  nohup xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" \
  -derivedDataPath build/dd-mac > ~/supermux-mac/mac-test.log 2>&1 &'
# poll for "** TEST SUCCEEDED **"
ssh mac 'grep -E "Test Suite|TEST" ~/supermux-mac/mac-test.log | tail -5'
```

Expected: `** TEST SUCCEEDED **`. Individual test failures here are REAL cross-platform bugs (e.g. an assumption about UIKit in a "platform-neutral" test) — fix the test or the code, do not exclude a test just to go green unless it genuinely tests iOS-only behavior (then add it to the excludes with a YAML comment saying why).

- [ ] **Step 4: iOS test lane still green** (`xcodebuild test -scheme Supermux …` as in Task 13 Step 3). Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit.**

```bash
git add apps/iosApp/project.yml
git commit -m "test(mac): SupermuxMacTests target — shared unit suite runs on macOS"
```

---

### Task 16: XCUITest smoke — launch → paired → sessions visible

**Files:**
- Create: `apps/iosApp/SupermuxMacUITests/SmokeTests.swift`
- Modify: `apps/iosApp/project.yml`

- [ ] **Step 1: UITest target** in `project.yml` (after `SupermuxMacTests`):

```yaml
  SupermuxMacUITests:
    type: bundle.ui-testing
    platform: macOS
    sources:
      - path: SupermuxMacUITests
    dependencies:
      - target: SupermuxMac
    settings:
      base:
        GENERATE_INFOPLIST_FILE: "YES"
        MACOSX_DEPLOYMENT_TARGET: "26.0"
        TEST_TARGET_NAME: SupermuxMac
```

and add `SupermuxMacUITests: [test]` under the `SupermuxMac` scheme's `build.targets` + `- SupermuxMacUITests` under its `test.targets`.

- [ ] **Step 2: The smoke test.** Create `apps/iosApp/SupermuxMacUITests/SmokeTests.swift`:

```swift
import XCTest

/// Critical-path smoke: app launches, auto-pairs from env, reaches the workspace,
/// and shows the sessions sidebar. Requires SM_PAIR_TOKEN/SM_PAIR_BASE in the
/// runner env (see the xcodebuild invocation) pointing at a reachable broker.
final class SmokeTests: XCTestCase {
    func testLaunchPairAndShowSessions() {
        let app = XCUIApplication()
        let env = ProcessInfo.processInfo.environment
        app.launchEnvironment["SM_PAIR_TOKEN"] = env["SM_PAIR_TOKEN"] ?? ""
        app.launchEnvironment["SM_PAIR_BASE"] = env["SM_PAIR_BASE"] ?? ""
        app.launch()

        // Paired + synced: the workspace window exists and is not the pairing screen.
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 15))
        // The launcher affordance is a stable landmark in both shells.
        let anySessionUI = app.buttons["New Session"].firstMatch
        XCTAssertTrue(anySessionUI.waitForExistence(timeout: 30),
                      "workspace did not appear — pairing or WS sync failed")
    }
}
```

(If the New Session affordance's accessibility label differs — check `SessionsRailView.swift`/`IPadWorkspace.swift` for the actual button label/`accessibilityIdentifier` — use that string; add an `.accessibilityIdentifier("new-session")` to the button and match on it if the label is an SF-symbol-only image button.)

- [ ] **Step 3: Run it** (mint a fresh token first: `cd /home/ahmet/projects/supermux && bun run pair mac-uitest`):

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  nohup env SM_PAIR_TOKEN="<token>" SM_PAIR_BASE="http://100.84.92.82:9898" \
  xcodebuild test -scheme SupermuxMac -only-testing:SupermuxMacUITests \
  -destination "platform=macOS,arch=arm64" -derivedDataPath build/dd-mac \
  > ~/supermux-mac/mac-uitest.log 2>&1 &'
# poll for "** TEST SUCCEEDED **"
```

Expected: `** TEST SUCCEEDED **`. Note: UI tests need the Mac to have Accessibility/Automation permission for the test runner — if the run fails with an automation-permission error, that's a one-time Mac-side grant (System Settings → Privacy → Accessibility for Xcode Helper); report it to the user rather than looping.

- [ ] **Step 4: Commit.**

```bash
git add apps/iosApp/project.yml apps/iosApp/SupermuxMacUITests
git commit -m "test(mac): XCUITest smoke — launch, env auto-pair, workspace reachable"
```

---

### Task 17: Build-and-run helper for feel-tests

**Files:**
- Create: `scripts/mac-app-run.sh`

- [ ] **Step 1: The helper.** Create `scripts/mac-app-run.sh` (mirrors the watch plan's helper; sync → build → relaunch on the Mac → screenshot best-effort):

```bash
#!/usr/bin/env bash
# Build + relaunch the macOS app on the remote Mac, paired to the live broker.
# Usage: scripts/mac-app-run.sh <pair-token> [broker-base]
# Screenshot lands in ~/.cache/supermux-mac.png on THIS host (best-effort — needs
# the Mac's Screen Recording permission for sshd-spawned processes).
set -euo pipefail
TOKEN="${1:?usage: mac-app-run.sh <pair-token> [broker-base]}"
BASE="${2:-http://100.84.92.82:9898}"
cd "$(git rev-parse --show-toplevel)"

tar --exclude .git --exclude 'apps/shared/build' --exclude node_modules --exclude 'apps/iosApp/build' -czf - . \
  | ssh mac 'rm -rf ~/supermux-mac && mkdir -p ~/supermux-mac && tar -xzf - -C ~/supermux-mac'

ssh mac "source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  xcodebuild -scheme SupermuxMac -destination 'platform=macOS,arch=arm64' -derivedDataPath build/dd-mac \
  CODE_SIGN_IDENTITY='-' CODE_SIGNING_REQUIRED=YES CODE_SIGNING_ALLOWED=YES build"

ssh mac "pkill -x Supermux 2>/dev/null || true; \
  SM_PAIR_TOKEN='$TOKEN' SM_PAIR_BASE='$BASE' \
  ~/supermux-mac/apps/iosApp/build/dd-mac/Build/Products/Debug/Supermux.app/Contents/MacOS/Supermux \
  > ~/supermux-mac/app.log 2>&1 & sleep 12; pgrep -x Supermux"

ssh mac 'screencapture -x /tmp/supermux-mac.png 2>/dev/null || true'
scp -q mac:/tmp/supermux-mac.png /home/ahmet/.cache/supermux-mac.png 2>/dev/null || \
  echo "screenshot unavailable (Screen Recording permission not granted to SSH context)"
echo OK
```

- [ ] **Step 2: Make it executable + trial run.**

```bash
chmod +x scripts/mac-app-run.sh
cd /home/ahmet/projects/supermux && bun run pair mac-feel   # mint token
# from the worktree:
scripts/mac-app-run.sh "<token>"
```

Expected: `OK` and a PID printed from `pgrep`. If the screenshot line reports unavailable, that's fine — note it and move on (feel-testing then happens via TestFlight).

- [ ] **Step 3: Commit.**

```bash
git add scripts/mac-app-run.sh
git commit -m "chore(mac): build-and-run helper for remote-Mac feel tests"
```

---

### Task 18: Mac App Store / TestFlight release lane

**Files:**
- Create: `apps/iosApp/ExportOptionsMac.plist`
- Modify: `apps/iosApp/project.yml` (Release signing for the mac targets)
- Modify: `apps/iosApp/Supermux/Assets.xcassets/AppIcon.appiconset/Contents.json` (mac icon slots)

**Prereqs the user must do once in App Store Connect / the developer portal (STOP and ask if any is missing — do not improvise):**
1. Enable the **macOS platform on the existing `dev.supermux.app` App ID** (universal purchase) + APNs capability for mac.
2. Register a Mac App ID for the NSE: `dev.supermux.app.mac-push-nse`.
3. Create Mac App Store provisioning profiles: **"Supermux Mac App Store"** (app) and **"Supermux Mac NSE App Store"** (extension), for team `57L7J9XA89`, and install them on the remote Mac.
4. Add a macOS app to the App Store Connect listing (universal purchase attaches it to the existing app record).

- [ ] **Step 1: App icon mac slots.** Mac App Store validation requires mac icon sizes. In `Assets.xcassets/AppIcon.appiconset/Contents.json`, add mac idiom entries (reusing the existing 1024 artwork file name — check the folder for the actual PNG name and generate resized copies with `sips` on the Mac if missing):

```json
    { "idiom": "mac", "size": "16x16",   "scale": "1x", "filename": "mac16.png" },
    { "idiom": "mac", "size": "16x16",   "scale": "2x", "filename": "mac32.png" },
    { "idiom": "mac", "size": "32x32",   "scale": "1x", "filename": "mac32.png" },
    { "idiom": "mac", "size": "32x32",   "scale": "2x", "filename": "mac64.png" },
    { "idiom": "mac", "size": "128x128", "scale": "1x", "filename": "mac128.png" },
    { "idiom": "mac", "size": "128x128", "scale": "2x", "filename": "mac256.png" },
    { "idiom": "mac", "size": "256x256", "scale": "1x", "filename": "mac256.png" },
    { "idiom": "mac", "size": "256x256", "scale": "2x", "filename": "mac512.png" },
    { "idiom": "mac", "size": "512x512", "scale": "1x", "filename": "mac512.png" },
    { "idiom": "mac", "size": "512x512", "scale": "2x", "filename": "mac1024.png" }
```

Resize on the Mac: `for s in 16 32 64 128 256 512 1024; do sips -z $s $s <original1024>.png --out mac$s.png; done` (then copy them into the appiconset locally and commit).

- [ ] **Step 2: Release signing** — extend the mac target settings in `project.yml`:

```yaml
      # under SupermuxMac.settings:
      configs:
        Release:
          PROVISIONING_PROFILE_SPECIFIER: "Supermux Mac App Store"
          CODE_SIGN_IDENTITY: "Apple Distribution"
```

```yaml
      # under SupermuxMacPushNSE.settings:
      configs:
        Release:
          PROVISIONING_PROFILE_SPECIFIER: "Supermux Mac NSE App Store"
          CODE_SIGN_IDENTITY: "Apple Distribution"
```

- [ ] **Step 3: ExportOptionsMac.plist.** Create `apps/iosApp/ExportOptionsMac.plist`. First `cat apps/iosApp/ExportOptions.plist` and mirror its auth/team keys exactly, changing only the method/platform bits:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>export</string>
	<key>teamID</key>
	<string>57L7J9XA89</string>
	<!-- copy signingStyle / provisioningProfiles / any auth keys from ExportOptions.plist,
	     mapping bundle ids: dev.supermux.app → "Supermux Mac App Store",
	     dev.supermux.app.mac-push-nse → "Supermux Mac NSE App Store" -->
</dict>
</plist>
```

- [ ] **Step 4: Archive + export + upload on the Mac.** The upload mechanism must MIRROR the iOS one — read the ship section of `docs/superpowers/plans/2026-06-22-apple-watch-app.md` (it shipped TestFlight build 33 headlessly; it documents the exact keychain-unlock + upload commands used on this Mac) and reuse it with these deltas: scheme `SupermuxMac`, destination `platform=macOS`, export options `ExportOptionsMac.plist`, and the produced artifact is a `.pkg` (mac) instead of an `.ipa`. Command shape:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && xcodegen generate && \
  nohup xcodebuild -scheme SupermuxMac -destination "generic/platform=macOS" \
    -archivePath build/SupermuxMac.xcarchive archive > ~/supermux-mac/archive.log 2>&1 &'
# poll for ARCHIVE SUCCEEDED, then:
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-mac/apps/iosApp && \
  xcodebuild -exportArchive -archivePath build/SupermuxMac.xcarchive \
    -exportOptionsPlist ExportOptionsMac.plist -exportPath build/mac-export'
# upload: SAME tool + auth the iOS/watch flow uses (per the watch plan ship section)
```

Expected: build appears in App Store Connect → TestFlight → macOS. **STOP and hand off to the user for the actual TestFlight submission click / release decision** — uploading a build is outward-facing; get an explicit OK before the upload step if this is the first mac upload.

- [ ] **Step 5: Commit.**

```bash
git add apps/iosApp/project.yml apps/iosApp/ExportOptionsMac.plist apps/iosApp/Supermux/Assets.xcassets
git commit -m "chore(mac): Mac App Store release lane — signing, export options, mac app icons"
```

---

## Completion criteria (map back to the spec)

- [ ] Mac + iOS app targets and BOTH test lanes green on the remote Mac.
- [ ] Feature parity verified by hand via `scripts/mac-app-run.sh` or TestFlight: sessions list, chat (markdown incl. tables, tappable file paths → editor), terminal (typing + scrollback + predictive echo on a slow link), editor + file tree, launcher (+ draft persistence), Finish flow, archived sessions + filter, usage panel, dictation, display/VNC pane, notifications (banner on a real APNs push from TestFlight build), pairing + unpair/re-pair.
- [ ] Mac-specific: ⌘N opens launcher, session context-menu → new window works, window restore/resize behaves, sleep/wake reconnects (close lid or `pmset sleepnow` on the Mac, wake, confirm WS resumes).
- [ ] Dead-session banner renders on BOTH platforms when a session's agent dies (kill a test session's process via the broker to verify).
- [ ] TestFlight macOS build uploaded (after explicit user OK).
```
