import AppKit
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @StateObject private var model = ContentViewModel()
    @State private var isImportingImage = false
    @State private var isImageDropTargeted = false

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
        .fileImporter(
            isPresented: $isImportingImage,
            allowedContentTypes: [.image],
            allowsMultipleSelection: false
        ) { result in
            model.handleImageImport(result)
        }
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

            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Screenshot")
                        .font(.headline)
                    Spacer()
                    if model.selectedImagePath != nil {
                        Text("Image query active")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }

                screenshotDropZone

                HStack(spacing: 12) {
                    Button("Choose Image") {
                        isImportingImage = true
                    }
                    .buttonStyle(.bordered)

                    Button("Paste Image") {
                        model.pasteImageFromClipboard()
                    }
                    .buttonStyle(.bordered)

                    if model.selectedImagePath != nil {
                        Button("Clear") {
                            model.clearSelectedImage()
                        }
                        .buttonStyle(.bordered)
                    }
                }

                Text("If a screenshot is selected, it takes priority over the text box.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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
                .disabled(model.isRunning || !model.hasQueryInput)

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

    private var screenshotDropZone: some View {
        VStack(alignment: .leading, spacing: 10) {
            Group {
                if let preview = model.selectedImagePreview {
                    Image(nsImage: preview)
                        .resizable()
                        .scaledToFill()
                        .frame(height: 180)
                        .frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                } else {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.black.opacity(0.05))
                        .frame(height: 180)
                        .overlay(
                            VStack(spacing: 8) {
                                Image(systemName: "photo.on.rectangle.angled")
                                    .font(.system(size: 28))
                                Text("Choose, paste, or drop a screenshot here")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        )
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(isImageDropTargeted ? Color.accentColor : Color.black.opacity(0.08), lineWidth: isImageDropTargeted ? 2 : 1)
            )
            .onDrop(of: [UTType.fileURL.identifier], isTargeted: $isImageDropTargeted) { providers in
                model.handleImageDrop(providers: providers)
            }

            if let selectedImagePath = model.selectedImagePath {
                Text(selectedImagePath)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
                    .lineLimit(2)
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        if let response = model.response {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if response.sourceType == "image" || !response.profile.intent.isEmpty {
                        QueryContextCard(response: response)
                    }

                    if let best = response.best {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Best Match")
                                .font(.title2.weight(.semibold))
                            ReactionCard(
                                image: best,
                                highlight: true,
                                onCopy: { model.copyImage(path: best.path) },
                                onReveal: { model.revealImageInFinder(path: best.path) },
                                onOpen: { model.openImage(path: best.path) }
                            )
                        }
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Ranked Results")
                            .font(.title3.weight(.semibold))
                        LazyVGrid(columns: grid, alignment: .leading, spacing: 18) {
                            ForEach(response.ranked) { image in
                                ReactionCard(
                                    image: image,
                                    highlight: response.best?.id == image.id,
                                    onCopy: { model.copyImage(path: image.path) },
                                    onReveal: { model.revealImageInFinder(path: image.path) },
                                    onOpen: { model.openImage(path: image.path) }
                                )
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
                description: Text("Run a text query, choose a screenshot, or paste an image from the clipboard.")
            )
        }
    }
}

private struct QueryContextCard: View {
    let response: QueryResponse

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(response.sourceType == "image" ? "Screenshot Query" : "Query Context")
                    .font(.title3.weight(.semibold))
                Spacer()
                if let sourceType = response.sourceType {
                    Text(sourceType.capitalized)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }

            if !response.queryText.isEmpty {
                Text(response.queryText)
                    .font(.headline)
            }

            if !response.profile.intent.isEmpty {
                Text(response.profile.intent)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if !response.profile.desiredReactionTags.isEmpty {
                Text("Reaction tags: \(response.profile.desiredReactionTags.joined(separator: ", "))")
                    .font(.caption)
            }

            if let sourceOcrText = response.sourceOcrText, !sourceOcrText.isEmpty {
                Text("OCR: \(sourceOcrText)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color(nsColor: .windowBackgroundColor))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.black.opacity(0.08), lineWidth: 1)
        )
    }
}

private struct ReactionCard: View {
    let image: RankedImage
    let highlight: Bool
    let onCopy: () -> Void
    let onReveal: () -> Void
    let onOpen: () -> Void

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

                HStack(spacing: 10) {
                    Button("Copy", action: onCopy)
                    Button("Reveal", action: onReveal)
                    Button("Open", action: onOpen)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
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
    @Published var selectedImagePath: String?
    @Published var selectedImagePreview: NSImage?
    @Published var response: QueryResponse?
    @Published var statusMessage: String?
    @Published var errorMessage: String?
    @Published var isRunning = false

    private let backend = BackendClient()

    var hasQueryInput: Bool {
        if let selectedImagePath, !selectedImagePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return true
        }
        return !queryText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    init() {
        let backendRoot = ProjectLocator.defaultBackendRoot()
        self.backendRoot = backendRoot
        self.imagesDir = ProjectLocator.defaultImagesDir(backendRoot: backendRoot)
        self.queryText = ""
        self.selectedImagePath = nil
        self.selectedImagePreview = nil
        self.response = nil
        self.statusMessage = "Ready."
        self.errorMessage = nil
    }

    func loadSample() {
        clearSelectedImage()
        queryText = "when someone says the dumbest thing imaginable with full confidence"
    }

    func clearSelectedImage() {
        selectedImagePath = nil
        selectedImagePreview = nil
    }

    func handleImageImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            setSelectedImage(url: url)
        case .failure(let error):
            errorMessage = "Could not import image: \(error.localizedDescription)"
        }
    }

    func handleImageDrop(providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) }) else {
            return false
        }

        provider.loadDataRepresentation(forTypeIdentifier: UTType.fileURL.identifier) { data, _ in
            guard let data, let url = URL(dataRepresentation: data, relativeTo: nil) else {
                return
            }
            Task { @MainActor in
                self.setSelectedImage(url: url)
            }
        }

        return true
    }

    func pasteImageFromClipboard() {
        guard let image = NSPasteboard.general.readObjects(forClasses: [NSImage.self])?.first as? NSImage else {
            errorMessage = "Clipboard does not currently contain an image."
            return
        }

        do {
            let targetURL = try writeClipboardImage(image)
            setSelectedImage(url: targetURL)
        } catch {
            errorMessage = "Could not save pasted image: \(error.localizedDescription)"
        }
    }

    func copyImage(path: String) {
        let url = URL(fileURLWithPath: path)
        guard let image = NSImage(contentsOf: url) else {
            errorMessage = "Could not read the image to copy it."
            return
        }

        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        if pasteboard.writeObjects([image, url as NSURL]) {
            errorMessage = nil
            statusMessage = "Copied image to the clipboard."
        } else {
            errorMessage = "Could not copy the image to the clipboard."
        }
    }

    func revealImageInFinder(path: String) {
        let url = URL(fileURLWithPath: path)
        NSWorkspace.shared.activateFileViewerSelecting([url])
        errorMessage = nil
        statusMessage = "Revealed image in Finder."
    }

    func openImage(path: String) {
        let url = URL(fileURLWithPath: path)
        if NSWorkspace.shared.open(url) {
            errorMessage = nil
            statusMessage = "Opened image."
        } else {
            errorMessage = "Could not open the image."
        }
    }

    func runQuery() {
        let query = queryText.trimmingCharacters(in: .whitespacesAndNewlines)
        let backendRoot = backendRoot.trimmingCharacters(in: .whitespacesAndNewlines)
        let imagesDir = imagesDir.trimmingCharacters(in: .whitespacesAndNewlines)
        let selectedImagePath = selectedImagePath?.trimmingCharacters(in: .whitespacesAndNewlines)

        guard hasQueryInput else { return }

        isRunning = true
        errorMessage = nil
        statusMessage = selectedImagePath == nil ? "Running reaction search..." : "Analyzing screenshot..."

        Task {
            do {
                let response = try await Task.detached(priority: .userInitiated) {
                    if let selectedImagePath, !selectedImagePath.isEmpty {
                        return try self.backend.query(imagePath: selectedImagePath, imagesDir: imagesDir, backendRoot: backendRoot)
                    }
                    return try self.backend.query(text: query, imagesDir: imagesDir, backendRoot: backendRoot)
                }.value
                self.response = response
                if response.sourceType == "image" {
                    self.statusMessage = "Found \(response.ranked.count) ranked result(s) from the screenshot."
                } else {
                    self.statusMessage = "Found \(response.ranked.count) ranked result(s)."
                }
            } catch {
                self.response = nil
                self.errorMessage = error.localizedDescription
                self.statusMessage = "Search failed."
            }
            self.isRunning = false
        }
    }

    private func setSelectedImage(url: URL) {
        guard let image = NSImage(contentsOf: url) else {
            errorMessage = "The selected file could not be read as an image."
            return
        }

        selectedImagePath = url.path
        selectedImagePreview = image
        errorMessage = nil
        statusMessage = "Selected screenshot ready."
    }

    private func writeClipboardImage(_ image: NSImage) throws -> URL {
        guard let tiff = image.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiff),
              let pngData = bitmap.representation(using: .png, properties: [:]) else {
            throw BackendClientError.invalidResponse("Clipboard image could not be encoded as PNG.")
        }

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("clj-apt-reaction-image-clipboard-\(UUID().uuidString)")
            .appendingPathExtension("png")
        try pngData.write(to: url)
        return url
    }
}
