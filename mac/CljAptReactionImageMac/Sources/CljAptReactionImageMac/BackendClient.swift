import Foundation

struct QueryResponse: Decodable {
    let sourceType: String?
    let sourceImagePath: String?
    let sourceOcrText: String?
    let queryText: String
    let profile: QueryProfile
    let best: RankedImage?
    let alternates: [RankedImage]
    let ranked: [RankedImage]
}

struct QueryProfile: Decodable {
    let queryText: String
    let intent: String
    let desiredReactionTags: [String]
    let tone: [String]
    let keywords: [String]
}

struct RankedImage: Decodable, Identifiable {
    let id: String
    let path: String
    let caption: String
    let reactionTags: [String]
    let sceneTags: [String]
    let people: [String]
    let emotions: [String]
    let notes: String
    let visibleText: String
    let ocrText: String
    let reason: String?
}

struct BackendErrorEnvelope: Decodable {
    let error: BackendErrorPayload
}

struct BackendErrorPayload: Decodable {
    let type: String
    let message: String
}

enum BackendClientError: LocalizedError {
    case launchFailed(String)
    case backendFailure(String)
    case invalidResponse(String)

    var errorDescription: String? {
        switch self {
        case .launchFailed(let message):
            return message
        case .backendFailure(let message):
            return message
        case .invalidResponse(let message):
            return message
        }
    }
}

struct BackendClient {
    func query(text: String, imagesDir: String, backendRoot: String) throws -> QueryResponse {
        try query(arguments: [
            "--text",
            text
        ], imagesDir: imagesDir, backendRoot: backendRoot)
    }

    func query(imagePath: String, imagesDir: String, backendRoot: String) throws -> QueryResponse {
        try query(arguments: [
            "--image",
            imagePath
        ], imagesDir: imagesDir, backendRoot: backendRoot)
    }

    private func query(arguments: [String], imagesDir: String, backendRoot: String) throws -> QueryResponse {
        let output = try run(
            arguments: [
                "-M:run",
                "query",
            ] + arguments + [
                "--images-dir",
                imagesDir,
                "--output",
                "json"
            ],
            backendRoot: backendRoot
        )

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase

        if output.exitCode == 0 {
            do {
                return try decoder.decode(QueryResponse.self, from: output.stdout)
            } catch {
                throw BackendClientError.invalidResponse("Could not decode query response: \(error.localizedDescription)")
            }
        }

        if let envelope = try? decoder.decode(BackendErrorEnvelope.self, from: output.stdout) {
            throw BackendClientError.backendFailure(envelope.error.message)
        }

        let stderr = String(data: output.stderr, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let stdout = String(data: output.stdout, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let message = [stderr, stdout]
            .compactMap { $0 }
            .first(where: { !$0.isEmpty }) ?? "The Clojure backend failed."
        throw BackendClientError.backendFailure(message)
    }

    private func run(arguments: [String], backendRoot: String) throws -> ProcessOutput {
        let process = Process()
        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()

        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["clojure"] + arguments
        process.currentDirectoryURL = URL(fileURLWithPath: backendRoot, isDirectory: true)
        process.standardOutput = stdoutPipe
        process.standardError = stderrPipe

        do {
            try process.run()
        } catch {
            throw BackendClientError.launchFailed("Could not start `clojure`: \(error.localizedDescription)")
        }

        process.waitUntilExit()

        let stdout = stdoutPipe.fileHandleForReading.readDataToEndOfFile()
        let stderr = stderrPipe.fileHandleForReading.readDataToEndOfFile()

        return ProcessOutput(
            stdout: stdout,
            stderr: stderr,
            exitCode: Int(process.terminationStatus)
        )
    }
}

private struct ProcessOutput {
    let stdout: Data
    let stderr: Data
    let exitCode: Int
}
