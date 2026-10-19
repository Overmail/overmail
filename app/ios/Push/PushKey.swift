import CryptoKit
import Foundation
import Security

/// The key what a notification shows is sealed for: P-256, made once per installation.
///
/// The app makes it and gives the server its public half; the notification service extension
/// opens what the server sealed with the private half. Both read it from the keychain, from the
/// group both targets list first in their `keychain-access-groups` entitlement -- the default for
/// an item that names no group, so neither has to know the team prefix.
///
/// Readable after the first unlock: a push arrives while the device is locked as well.
enum PushKey {
    private static let service = "es.jvbabi.overmail.push"
    private static let account = "content-key"

    /// The key, made and stored when there is none yet. Only the app calls this.
    static func loadOrCreate() throws -> P256.KeyAgreement.PrivateKey {
        if let key = try load() { return key }

        let key = P256.KeyAgreement.PrivateKey()
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: key.rawRepresentation,
        ]
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw PushKeyError.keychain(status) }
        return key
    }

    /// The key, or nil when the app never made one.
    static func load() throws -> P256.KeyAgreement.PrivateKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw PushKeyError.keychain(status) }
        return try P256.KeyAgreement.PrivateKey(rawRepresentation: data)
    }
}

enum PushKeyError: Error {
    case keychain(OSStatus)
}
