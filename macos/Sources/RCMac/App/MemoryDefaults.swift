import Foundation

/// Defaults that never reach the disk, for an ephemeral run: the stores read
/// and write `UserDefaults`, and a suite of it — even one removed on quit —
/// goes through `cfprefsd`, which writes its file back after it is deleted.
/// Every accessor the stores use is answered from memory, and nothing is
/// handed to the real defaults system at all. `UserDefaults` is used from any
/// thread, so the values are only ever touched under the lock.
final class MemoryDefaults: UserDefaults {
    private let lock = NSLock()
    private var values: [String: Any] = [:]

    init() {
        // A search list with no suite of its own; every read and write below
        // is answered without it.
        super.init(suiteName: nil)!
    }

    override func object(forKey defaultName: String) -> Any? { lock.withLock { values[defaultName] } }

    override func set(_ value: Any?, forKey defaultName: String) {
        lock.withLock { values[defaultName] = value }
    }

    override func removeObject(forKey defaultName: String) {
        lock.withLock { _ = values.removeValue(forKey: defaultName) }
    }

    override func set(_ value: Bool, forKey defaultName: String) { set(value as Any?, forKey: defaultName) }
    override func set(_ value: Int, forKey defaultName: String) { set(value as Any?, forKey: defaultName) }
    override func set(_ value: Double, forKey defaultName: String) { set(value as Any?, forKey: defaultName) }
    override func set(_ value: Float, forKey defaultName: String) { set(value as Any?, forKey: defaultName) }
    override func set(_ url: URL?, forKey defaultName: String) { set(url as Any?, forKey: defaultName) }

    override func string(forKey defaultName: String) -> String? { object(forKey: defaultName) as? String }
    override func stringArray(forKey defaultName: String) -> [String]? { object(forKey: defaultName) as? [String] }
    override func array(forKey defaultName: String) -> [Any]? { object(forKey: defaultName) as? [Any] }
    override func dictionary(forKey defaultName: String) -> [String: Any]? {
        object(forKey: defaultName) as? [String: Any]
    }
    override func data(forKey defaultName: String) -> Data? { object(forKey: defaultName) as? Data }
    override func bool(forKey defaultName: String) -> Bool { (object(forKey: defaultName) as? Bool) ?? false }
    override func integer(forKey defaultName: String) -> Int { (object(forKey: defaultName) as? Int) ?? 0 }
    override func double(forKey defaultName: String) -> Double { (object(forKey: defaultName) as? Double) ?? 0 }
    override func float(forKey defaultName: String) -> Float { (object(forKey: defaultName) as? Float) ?? 0 }
    override func url(forKey defaultName: String) -> URL? { object(forKey: defaultName) as? URL }

    override func dictionaryRepresentation() -> [String: Any] { lock.withLock { values } }

    override func synchronize() -> Bool { true }
}
