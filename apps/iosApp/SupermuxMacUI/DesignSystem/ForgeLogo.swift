import SwiftUI

/// Git hosting brand marks shared by onboarding, Settings, and connection rows.
struct ForgeLogo: View {
    let kind: String
    var size: CGFloat = 20

    var body: some View {
        Group {
            if kind.lowercased() == "gitlab" {
                Image("gitlab")
                    .resizable()
                    .renderingMode(.original)
                    .scaledToFit()
            } else {
                Image("github")
                    .resizable()
                    .renderingMode(.template)
                    .scaledToFit()
                    .foregroundStyle(.primary)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
