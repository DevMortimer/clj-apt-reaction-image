import AppKit
import SwiftUI

struct ContentView: View {
    @StateObject private var model = ContentViewModel()

    private let grid = [
        GridItem(.adaptive(minimum: 240, maximum: 320), spacing: 18)
    ]

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detail
        }
        .navigationSplitViewStyle(.balanced)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("clj-apt-reaction-image")
                    .font(.system(size: 26, weight: .semibold, design: .rounded))
                Text("Rank reaction images from a conversation snippet.")
                    .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("Backend Root")
                    .font(.headline)
                TextField("Backend root", text: $model.backendRoot)
                    .textFieldStyle(.roundedBorder)

                Text("Images Directory")
                    .font(.headline)
                TextField("Images directory", text: $model.imagesDir)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("Conversation")
                    .font(.headline)
                TextEditor(text: $model.queryText)
                    .font(.system(.body, design: .rounded))
                    .scrollContentBackground(.hidden)
                    .padding(10)
                    .frame(minHeight: 220)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Color(nsColor: .textBackgroundColor))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(Color.black.opacity(0.08), lineWidth: 1)
                    )
            }

            HStack(spacing: 12) {
                Button(action: model.runQuery) {
                    if model.isRunning {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Text("Find Reaction")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isRunning || model.queryText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Button("Use Sample") {
                    model.loadSample()
                }
                .buttonStyle(.bordered)
            }

            if let status = model.statusMessage {
                Text(status)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let error = model.errorMessage {
                Text(error)
                    .font(.subheadline)
                    .foregroundStyle(.red)
            }

            Spacer()
        }
        .padding(24)
        .frame(minWidth: 340)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    @ViewBuilder
    private var detail: some View {
        if let response = model.response {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let best = response.best {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Best Match")
                                .font(.title2.weight(.semibold))
                            ReactionCard(image: best, highlight: true)
                        }
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Ranked Results")
                            .font(.title3.weight(.semibold))
                        LazyVGrid(columns: grid, alignment: .leading, spacing: 18) {
                            ForEach(response.ranked) { image in
                                ReactionCard(image: image, highlight: response.best?.id == image.id)
                            }
                        }
                    }
                }
                .padding(24)
            }
            .background(Color(nsColor: .controlBackgroundColor))
        } else {
            ContentUnavailableView(
                "No Results Yet",
                systemImage: "photo.stack",
                description: Text("Run a text query first. Screenshot input can slot into the same backend contract next.")
            )
        }
    }
}

private struct ReactionCard: View {
    let image: RankedImage
    let highlight: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            thumbnail

            VStack(alignment: .leading, spacing: 8) {
                if !image.caption.isEmpty {
                    Text(image.caption)
                        .font(.headline)
                }

                if let reason = image.reason, !reason.isEmpty {
                    Text(reason)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else if !image.notes.isEmpty {
                    Text(image.notes)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                tagRow(title: "Reaction", values: image.reactionTags)
                tagRow(title: "Emotion", values: image.emotions)

                Text(image.path)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
                    .lineLimit(2)
            }
        }
        .padding(14)
        .background(cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(highlight ? Color.accentColor.opacity(0.45) : Color.black.opacity(0.08), lineWidth: 1)
        )
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let nsImage = NSImage(contentsOfFile: image.path) {
            Image(nsImage: nsImage)
                .resizable()
                .scaledToFill()
                .frame(height: 190)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.black.opacity(0.06))
                .frame(height: 190)
                .overlay(
                    Image(systemName: "photo")
                        .font(.system(size: 32))
                        .foregroundStyle(.secondary)
                )
        }
    }

    private var cardBackground: some ShapeStyle {
        highlight
            ? AnyShapeStyle(
                LinearGradient(
                    colors: [
                        Color.accentColor.opacity(0.14),
                        Color(nsColor: .windowBackgroundColor)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            : AnyShapeStyle(Color(nsColor: .windowBackgroundColor))
    }

    @ViewBuilder
    private func tagRow(title: String, values: [String]) -> some View {
        if !values.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(values.joined(separator: ", "))
                    .font(.caption)
            }
        }
    }
}

@MainActor
final class ContentViewModel: ObservableObject {
    @Published var backendRoot: String
    @Published var imagesDir: String
    @Published var queryText: String
    @Published var response: QueryResponse?
    @Published var statusMessage: String?
    @Published var errorMessage: String?
    @Published var isRunning = false

    private let backend = BackendClient()

    init() {
        let backendRoot = ProjectLocator.defaultBackendRoot()
        self.backendRoot = backendRoot
        self.imagesDir = ProjectLocator.defaultImagesDir(backendRoot: backendRoot)
        self.queryText = ""
        self.response = nil
        self.statusMessage = "Ready."
        self.errorMessage = nil
    }

    func loadSample() {
        queryText = "when someone says the dumbest thing imaginable with full confidence"
    }

    func runQuery() {
        let query = queryText.trimmingCharacters(in: .whitespacesAndNewlines)
        let backendRoot = backendRoot.trimmingCharacters(in: .whitespacesAndNewlines)
        let imagesDir = imagesDir.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !query.isEmpty else { return }

        isRunning = true
        errorMessage = nil
        statusMessage = "Running reaction search..."

        Task {
            do {
                let response = try await Task.detached(priority: .userInitiated) {
                    try self.backend.query(text: query, imagesDir: imagesDir, backendRoot: backendRoot)
                }.value
                self.response = response
                self.statusMessage = "Found \(response.ranked.count) ranked result(s)."
            } catch {
                self.response = nil
                self.errorMessage = error.localizedDescription
                self.statusMessage = "Search failed."
            }
            self.isRunning = false
        }
    }
}
