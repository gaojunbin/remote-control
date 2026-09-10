import Foundation
#if canImport(Security)
import Security
#endif

public protocol SecretStore: Sendable {
    func read(key: String) async throws -> Data?
    func write(_ data: Data, key: String) async throws
    func remove(key: String) async throws
}

/// For tests and explicitly ephemeral sessions. Never writes credentials to disk.
public actor MemorySecretStore: SecretStore {
    private var values: [String: Data] = [:]
    public init() {}
    public func read(key: String) -> Data? { values[key] }
    public func write(_ data: Data, key: String) { values[key] = data }
    public func remove(key: String) { values.removeValue(forKey: key) }
}

/// Keychain storage for the gateway bearer token.
///
/// The item is device-only and never synchronises to iCloud, so signing in on a
/// second device is a deliberate act rather than a side effect of a backup.
public actor KeychainSecretStore: SecretStore {
    private let service: String

    public init(service: String = "com.junbingao.remotecontrol.gateway") { self.service = service }

    public func read(key: String) throws -> Data? {
        #if canImport(Security)
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw TransportError.secureStorageUnavailable
        }
        return data
        #else
        throw TransportError.secureStorageUnavailable
        #endif
    }

    public func write(_ data: Data, key: String) throws {
        #if canImport(Security)
        let query = baseQuery(key)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
                throw TransportError.secureStorageUnavailable
            }
        } else if status != errSecSuccess {
            throw TransportError.secureStorageUnavailable
        }
        #else
        throw TransportError.secureStorageUnavailable
        #endif
    }

    public func remove(key: String) throws {
        #if canImport(Security)
        let status = SecItemDelete(baseQuery(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw TransportError.secureStorageUnavailable
        }
        #else
        throw TransportError.secureStorageUnavailable
        #endif
    }

    #if canImport(Security)
    private func baseQuery(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key,
         kSecAttrSynchronizable as String: false]
    }
    #endif
}
