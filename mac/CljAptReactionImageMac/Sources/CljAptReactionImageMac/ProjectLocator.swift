import Foundation

enum ProjectLocator {
    static func defaultBackendRoot() -> String {
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true)
        if let root = findBackendRoot(startingAt: cwd) {
            return root.path
        }
        return cwd.path
    }

    static func defaultImagesDir(backendRoot: String) -> String {
        URL(fileURLWithPath: backendRoot, isDirectory: true)
            .appendingPathComponent("datasets", isDirectory: true)
            .path
    }

    private static func findBackendRoot(startingAt url: URL) -> URL? {
        var current = url.standardizedFileURL
        while true {
            let deps = current.appendingPathComponent("deps.edn")
            let core = current
                .appendingPathComponent("src", isDirectory: true)
                .appendingPathComponent("clj_apt_reaction_image", isDirectory: true)
                .appendingPathComponent("core.clj")
            if FileManager.default.fileExists(atPath: deps.path),
               FileManager.default.fileExists(atPath: core.path) {
                return current
            }

            let parent = current.deletingLastPathComponent()
            if parent.path == current.path {
                return nil
            }
            current = parent
        }
    }
}
