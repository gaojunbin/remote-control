import Foundation

/// Reads the command line, renders what it names and says what it wrote.
@MainActor
enum PreviewCommand {
    static func run(_ argv: [String]) async -> Int32 {
        let arguments: PreviewArguments
        do {
            arguments = try PreviewArguments(argv)
        } catch {
            FileHandle.standardError.write(Data("\(error)\n\(PreviewArguments.usage)\n".utf8))
            return 64
        }
        let registry = PreviewScenarios.all
        if arguments.list {
            for scenario in registry { print(scenario.name) }
            return 0
        }
        let chosen = arguments.all ? registry : registry.filter { arguments.scenarios.contains($0.name) }
        let unknown = Set(arguments.scenarios).subtracting(registry.map(\.name))
        guard unknown.isEmpty else {
            FileHandle.standardError.write(Data("unknown scenario: \(unknown.sorted().joined(separator: ", "))\n".utf8))
            return 64
        }
        guard let out = arguments.out else { return 64 }
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let renderer = PreviewRenderer(arguments: arguments)
        var failures = 0
        for scenario in chosen {
            do {
                let file = try await renderer.render(scenario, to: out)
                print("rendered \(scenario.name) → \(file.path)")
            } catch {
                failures += 1
                FileHandle.standardError.write(Data("failed \(scenario.name): \(error)\n".utf8))
            }
        }
        print("\(chosen.count - failures) of \(chosen.count) scenarios rendered")
        return failures == 0 ? 0 : 1
    }
}
