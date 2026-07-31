import Foundation

let args = CommandLine.arguments
guard args.count == 2 || args.count == 3 else {
    print("Usage: tags <file-path> [tag1,tag2,...]")
    print("  with tags: set Finder tags")
    print("  without:   print current Finder tags, comma-separated")
    exit(1)
}

let filePath = args[1]
var url = URL(fileURLWithPath: filePath)

do {
    if args.count == 2 {
        let values = try url.resourceValues(forKeys: [.tagNamesKey])
        print((values.tagNames ?? []).joined(separator: ","))
    } else {
        let tags = args[2].split(separator: ",").map(String.init)
        var resourceValues = URLResourceValues()
        resourceValues.tagNames = tags
        try url.setResourceValues(resourceValues)
    }
} catch {
    print("Error: \(error.localizedDescription)")
    exit(1)
}
