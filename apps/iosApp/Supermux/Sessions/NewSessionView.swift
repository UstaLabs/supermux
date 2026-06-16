import SwiftUI
import Shared

/// New-session launcher — pick a project + agent, optional name, then spawn.
/// Mirrors the web SessionLauncherView (minus worktree/branch options for now).
struct NewSessionView: View {
    let broker: BrokerSession
    var onSpawned: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var projects: [String] = []
    @State private var selected = ""
    @State private var agent = "claude"
    @State private var name = ""
    @State private var spawning = false

    private let agents = ["claude", "codex", "cursor", "opencode"]

    var body: some View {
        Form {
            Section("Project") {
                if projects.isEmpty {
                    HStack { ProgressView(); Text("Loading projects…").foregroundStyle(.secondary) }
                } else {
                    Picker("Project", selection: $selected) {
                        ForEach(projects, id: \.self) { p in
                            Text(formatWorkdir(workdir: p, home: inferHomeDir(workdir: p))).tag(p)
                        }
                    }
                }
            }
            Section("Agent") {
                Picker("Agent", selection: $agent) {
                    ForEach(agents, id: \.self) { a in
                        HStack { AgentLogo(agent: a, size: 22); Text(a.capitalized) }.tag(a)
                    }
                }
                .pickerStyle(.inline)
            }
            Section("Name") {
                TextField("optional — auto-named", text: $name)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
            }
            Section {
                Button(action: spawn) {
                    HStack {
                        Spacer()
                        if spawning { ProgressView().tint(.white) }
                        else { Text("Start session").fontWeight(.semibold) }
                        Spacer()
                    }
                    .foregroundStyle(.white).padding(.vertical, 4)
                }
                .listRowBackground(selected.isEmpty ? Color.gray.opacity(0.4) : Theme.teal)
                .disabled(selected.isEmpty || spawning)
            }
        }
        .navigationTitle("New session")
        .navigationBarTitleDisplayMode(.inline)
        .tint(Theme.teal)
        .task {
            projects = await broker.projects()
            if selected.isEmpty { selected = projects.first ?? "" }
        }
    }

    private func spawn() {
        spawning = true
        Task {
            let id = await broker.spawn(workdir: selected, agent: agent, name: name.isEmpty ? nil : name)
            spawning = false
            dismiss()
            if let id, !id.isEmpty { onSpawned(id) }
        }
    }
}
