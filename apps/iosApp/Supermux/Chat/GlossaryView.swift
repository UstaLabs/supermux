import SwiftUI

/// Editor for the voice-cleanup glossary — the user-managed list of project/technical
/// terms (e.g. "Supermux", "Codex") that (1) the broker injects into the codex cleanup
/// prompt so it stops mis-correcting them, and (2) the on-device recognizer takes as
/// contextual hints so it gets them right at the source.
///
/// The glossary lives on the broker (shared across devices). We load it via
/// `fetchGlossary` on appear and persist every edit immediately via `updateGlossary`.
struct GlossaryView: View {
    let broker: BrokerSession

    @State private var terms: [String] = []
    @State private var newTerm = ""
    @State private var loading = true
    @State private var saving = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss
    @FocusState private var addFocused: Bool

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    list
                }
            }
            .navigationTitle("Voice glossary")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task { await load() }
    }

    private var list: some View {
        List {
            Section {
                HStack(spacing: 8) {
                    TextField("Add a term (e.g. Supermux)", text: $newTerm)
                        .focused($addFocused)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { add() }
                    Button { add() } label: { Image(systemName: "plus.circle.fill") }
                        .disabled(trimmedNew.isEmpty)
                }
            } footer: {
                Text("Terms the agent should keep spelled exactly, and that on-device dictation is biased toward.")
            }

            Section {
                if terms.isEmpty {
                    Text("No terms yet — add the names dictation keeps getting wrong.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(terms, id: \.self) { Text($0) }
                        .onDelete(perform: delete)
                }
            } header: {
                if let error {
                    Text(error).foregroundStyle(.red).textCase(nil)
                } else if saving {
                    Text("Saving…").textCase(nil)
                }
            }
        }
    }

    private var trimmedNew: String { newTerm.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            terms = try await broker.fetchGlossary()
            error = nil
        } catch {
            self.error = "Couldn't load the glossary"
        }
    }

    private func add() {
        let term = trimmedNew
        guard !term.isEmpty else { return }
        // Case-insensitive dedupe so "Supermux"/"supermux" don't both land.
        guard !terms.contains(where: { $0.caseInsensitiveCompare(term) == .orderedSame }) else {
            newTerm = ""
            return
        }
        terms.append(term)
        newTerm = ""
        addFocused = true
        persist()
    }

    private func delete(at offsets: IndexSet) {
        terms.remove(atOffsets: offsets)
        persist()
    }

    /// Persist the full list. On failure, reload from the broker so the UI reflects
    /// what's actually stored (rather than leaving an unsaved edit on screen).
    private func persist() {
        let snapshot = terms
        saving = true
        error = nil
        Task {
            defer { saving = false }
            do {
                try await broker.updateGlossary(snapshot)
            } catch {
                self.error = "Couldn't save — reverted"
                await load()
            }
        }
    }
}
