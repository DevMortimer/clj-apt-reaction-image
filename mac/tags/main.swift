import Foundation

let args = CommandLine.arguments
guard args.count == 3 else {
    print("Usage: tags <file-path> <tag1,tag2,...>")
    exit(1)
}

let filePath = args[1]
let tags = args[2].split(separator: ",").map(String.init)
var url = URL(fileURLWithPath: filePath)

do {
    var resourceValues = URLResourceValues()
    resourceValues.tagNames = tags
    try url.setResourceValues(resourceValues)
} catch {
    print("Error: \(error.localizedDescription)")
    exit(1)
}
