import SwiftUI
import Shared

/// Compose-first launcher — mirrors the web SessionLauncherView: centered
/// "Let's build", a project dropdown, and a compose card (agent picker + the
/// first message) that spawns the session and sends that message on ↑.
struct NewSessionView: View {
    let broker: BrokerSession
    var onSpawned: (String) -> Void

    @State private var projects: [String] = []
    @State private var workdir = ""
    @State private var agent = "claude"
    @State private var model: String?
    @State private var models: [ModelInfo] = []
    @State private var projectSearch = false
    @State private var draft = ""
    @State private var spawning = false
    @FocusState private var composing: Bool

    private let agents = ["claude", "codex", "cursor", "opencode"]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer().frame(height: 28)
                Image(systemName: "cube.fill").font(.system(size: 38)).foregroundStyle(.primary)
                Text("Let's build").font(.largeTitle.bold())
                projectPicker
                composeCard
                if !workdir.isEmpty {
                    Label(workdirLabel, systemImage: "folder")
                        .font(.caption.monospaced()).foregroundStyle(.secondary).padding(.top, 2)
                }
            }
            .padding(20).frame(maxWidth: .infinity)
        }
        .navigationTitle("New session").navigationBarTitleDisplayMode(.inline)
        .tint(Theme.teal)
        .task {
            projects = await broker.projects()
            if workdir.isEmpty { workdir = projects.first ?? "~" }
        }
        .task(id: agent) {
            models = await broker.listModels(agent)
            model = nil
        }
        .sheet(isPresented: $projectSearch) {
            ProjectPickerSheet(projects: projects, current: workdir) { workdir = $0 }
        }
    }

    private var workdirLabel: String {
        formatWorkdir(workdir: workdir, home: inferHomeDir(workdir: workdir))
    }

    private var projectPicker: some View {
        Button { projectSearch = true } label: {
            HStack(spacing: 6) {
                Text(workdir.isEmpty ? "Select project" : workdirLabel)
                    .font(.title2.weight(.semibold)).foregroundStyle(.secondary)
                Image(systemName: "chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
        }
    }
    private var modelLabel: String {
        guard let model else { return "Default" }
        return models.first { $0.id == model }?.displayName ?? model
    }

    private var composeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("What should the agent do?", text: $draft, axis: .vertical)
                .lineLimit(3...8).focused($composing)
            HStack(spacing: 16) {
                Menu {
                    ForEach(agents, id: \.self) { a in Button(a.capitalized) { agent = a } }
                } label: {
                    HStack(spacing: 5) {
                        AgentLogo(agent: agent, size: 18)
                        Text(agent.capitalized).font(.subheadline.weight(.medium))
                        Image(systemName: "chevron.down").font(.caption2)
                    }.foregroundStyle(.primary)
                }
                if !models.isEmpty {
                    Menu {
                        Button("Default") { model = nil }
                        ForEach(models, id: \.id) { m in Button(m.displayName) { model = m.id } }
                    } label: {
                        HStack(spacing: 4) {
                            Text(modelLabel).font(.subheadline.weight(.medium))
                            Image(systemName: "chevron.down").font(.caption2)
                        }.foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button(action: spawn) {
                    if spawning {
                        ProgressView().tint(.white).frame(width: 40, height: 40)
                    } else {
                        Image(systemName: "arrow.up").font(.headline.weight(.bold)).foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(canSpawn ? Theme.teal : Color.gray.opacity(0.5), in: Circle())
                    }
                }
                .disabled(!canSpawn || spawning)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var canSpawn: Bool { !workdir.isEmpty }

    private func spawn() {
        spawning = true
        let firstMsg = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            let id = await broker.spawn(workdir: workdir, agent: agent, name: nil, model: model)
            if let id, !id.isEmpty {
                if !firstMsg.isEmpty { broker.send(id, firstMsg) }
                onSpawned(id)
            }
            spawning = false
        }
    }
}

/// Searchable project picker — filter the list or type an arbitrary path.
private struct ProjectPickerSheet: View {
    let projects: [String]
    let current: String
    var onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var query: String { search.trimmingCharacters(in: .whitespaces) }
    private var filtered: [String] {
        let q = query.lowercased()
        return q.isEmpty ? projects : projects.filter { $0.lowercased().contains(q) }
    }

    var body: some View {
        NavigationStack {
            List {
                if !query.isEmpty, !projects.contains(query) {
                    Button { onPick(query); dismiss() } label: {
                        Label("Use “\(query)”", systemImage: "folder.badge.plus")
                    }
                }
                ForEach(filtered, id: \.self) { p in
                    Button {
                        onPick(p); dismiss()
                    } label: {
                        HStack {
                            Text(formatWorkdir(workdir: p, home: inferHomeDir(workdir: p)))
                                .foregroundStyle(.primary).lineLimit(1)
                            Spacer()
                            if p == current { Image(systemName: "checkmark").foregroundStyle(Theme.teal) }
                        }
                    }
                }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Search or enter a path")
            .navigationTitle("Project").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Cancel") { dismiss() } } }
        }
        .tint(Theme.teal)
    }
}
