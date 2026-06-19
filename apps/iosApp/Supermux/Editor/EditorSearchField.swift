import SwiftUI
import Shared

/// Debounced filename search for the editor header. A glass search field with a
/// live results dropdown — mirrors the Android `EditorSearchField` + overlay, but
/// native (no scrim; the parent owns presentation). The `search` closure is
/// `broker.fsSearch` (capped at 20 server-side); ignored results render dimmed.
///
/// HIG: results appear live as you type; we do NOT auto-focus (auto-focus on iPad
/// hides results behind the keyboard). Selecting a result opens it and clears the
/// query + results.
struct EditorSearchField: View {
    let search: (String) async -> [FsSearchResult]
    let onOpen: (String) -> Void

    @State private var query = ""
    @State private var results: [FsSearchResult] = []
    private let selection = UISelectionFeedbackGenerator()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            field
            if !results.isEmpty { resultsList }
        }
        // Debounced search (200 ms). `.task(id:)` cancels the prior run automatically
        // whenever `query` changes — so each keystroke restarts the timer.
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !q.isEmpty else { results = []; return }
            try? await Task.sleep(nanoseconds: 200_000_000)
            if Task.isCancelled { return }
            let hits = await search(q)
            if Task.isCancelled { return }
            results = hits
        }
    }

    private var field: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.footnote)
                .foregroundStyle(.secondary)
            TextField("Search files…", text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.callout)
                .submitLabel(.search)
            if !query.isEmpty {
                Button {
                    query = ""
                    results = []
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var resultsList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(results, id: \.path) { result in
                    Button {
                        selection.selectionChanged()
                        onOpen(result.path)
                        query = ""
                        results = []
                    } label: {
                        Text(result.path)
                            .font(.caption.monospaced())
                            .lineLimit(2)
                            .truncationMode(.middle)
                            .foregroundStyle(.primary)
                            .opacity(result.ignored ? 0.5 : 1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(result.path)
                    if result.path != results.last?.path {
                        Divider()
                    }
                }
            }
        }
        .frame(maxHeight: 240)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Theme.hairline, lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
    }
}
