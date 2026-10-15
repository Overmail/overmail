import CryptoKit
import Foundation

/// What the notification of a push shows, as the server sealed it (`ApplePushContent`). A field
/// that is missing is left to the extension's own text.
struct PushContent: Decodable, Equatable {
    let sender: String?
    let subject: String?
    let preview: String?
}

/// Opens what the server sealed for this device, the other half of `ApplePushSealing.kt`.
///
/// Version 1, ECIES on P-256:
/// `0x01 || ephemeral public key (65, X9.63) || nonce (12) || AES-256-GCM ciphertext || tag (16)`,
/// the AES key HKDF-SHA256 over the ECDH secret, no salt, and
/// `"overmail-push-v1" || ephemeral key || device key` as its info.
enum PushContentDecryptor {
    static let version: UInt8 = 1

    private static let pointSize = 65
    private static let headerSize = 1 + pointSize

    static func open(_ sealed: Data, with key: P256.KeyAgreement.PrivateKey) throws -> PushContent {
        let sealed = Data(sealed) // indices from 0, whatever slice came in
        guard sealed.count > headerSize + 12 + 16, sealed[0] == version else { throw PushContentError.unknownFormat }

        let ephemeralKey = sealed.subdata(in: 1..<headerSize)
        let ephemeral = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralKey)
        let secret = try key.sharedSecretFromKeyAgreement(with: ephemeral)
        let info = Data("overmail-push-v1".utf8) + ephemeralKey + key.publicKey.x963Representation
        let symmetricKey = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: info, outputByteCount: 32)

        let box = try AES.GCM.SealedBox(combined: sealed.subdata(in: headerSize..<sealed.count))
        // The json is padded with spaces, which a decoder takes as whitespace after the value.
        return try JSONDecoder().decode(PushContent.self, from: AES.GCM.open(box, using: symmetricKey))
    }
}

enum PushContentError: Error {
    case unknownFormat
}
