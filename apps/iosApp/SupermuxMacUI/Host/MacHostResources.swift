#if os(macOS)
import Foundation

enum MacHostResources {
    struct Binaries: Equatable {
        let broker: URL
        let frpc: URL?
        let tmux: URL?
        let binDirectory: URL
    }

    enum ResourceError: Error {
        case missingBroker
    }

    static func prepareBundled(
        bundle: Bundle = .main,
        stateDirectory: URL = MacBrokerSidecar.hostStateDirectory()
    ) throws -> Binaries {
        guard let resources = bundle.resourceURL?.appendingPathComponent("HostResources") else {
            throw ResourceError.missingBroker
        }
        return try prepare(resourceDirectory: resources, stateDirectory: stateDirectory)
    }

    static func prepare(resourceDirectory: URL, stateDirectory: URL) throws -> Binaries {
        let bin = stateDirectory.appendingPathComponent("bin", isDirectory: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)

        let brokerSource = resourceDirectory.appendingPathComponent("supermux-broker")
        guard FileManager.default.fileExists(atPath: brokerSource.path) else {
            throw ResourceError.missingBroker
        }

        return Binaries(
            broker: try materialize(brokerSource, into: bin),
            frpc: try optionalMaterialize(resourceDirectory.appendingPathComponent("frpc"), into: bin),
            tmux: try optionalMaterialize(resourceDirectory.appendingPathComponent("tmux"), into: bin),
            binDirectory: bin
        )
    }

    private static func optionalMaterialize(_ source: URL, into destination: URL) throws -> URL? {
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        return try materialize(source, into: destination)
    }

    private static func materialize(_ source: URL, into directory: URL) throws -> URL {
        let destination = directory.appendingPathComponent(source.lastPathComponent)
        let stamp = directory.appendingPathComponent(".\(source.lastPathComponent).stamp")
        let sourceAttributes = try FileManager.default.attributesOfItem(atPath: source.path)
        let size = (sourceAttributes[.size] as? NSNumber)?.int64Value ?? -1
        let modified = (sourceAttributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? -1
        let signature = "\(size):\(modified)"
        let installedSignature = try? String(contentsOf: stamp, encoding: .utf8)

        if !FileManager.default.fileExists(atPath: destination.path) || installedSignature != signature {
            let temporary = directory.appendingPathComponent(".\(source.lastPathComponent).\(UUID().uuidString).tmp")
            try? FileManager.default.removeItem(at: temporary)
            try FileManager.default.copyItem(at: source, to: temporary)
            try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: temporary.path)
            if FileManager.default.fileExists(atPath: destination.path) {
                _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary)
            } else {
                try FileManager.default.moveItem(at: temporary, to: destination)
            }
            try signature.write(to: stamp, atomically: true, encoding: .utf8)
        } else {
            try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: destination.path)
        }
        return destination
    }
}
#endif
