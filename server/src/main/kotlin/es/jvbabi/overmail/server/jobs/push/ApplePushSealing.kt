package es.jvbabi.overmail.server.jobs.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts what an iOS notification shows for the one device it goes to, so that APNs carries it
 * without being able to read it. The device made the key pair and keeps the private half; its
 * notification service extension opens what this seals, without asking the server.
 *
 * ECIES on P-256, version 1:
 *
 * ```
 * 0x01 || ephemeral public key (65, X9.63) || nonce (12) || AES-256-GCM ciphertext || tag (16)
 * ```
 *
 * The AES key is HKDF-SHA256 over the ECDH secret of the ephemeral and the device key, with no
 * salt and `"overmail-push-v1" || ephemeral key || device key` as its info. The other half is
 * `PushContentDecryptor.swift` in the extension; a change here is a new version there.
 */
object ApplePushSealing {
    const val VERSION: Byte = 1

    private const val POINT_SIZE = 65
    private const val COORDINATE_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private val INFO = "overmail-push-v1".encodeToByteArray()

    private val random = SecureRandom()

    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    /**
     * The device key in [x963], the uncompressed form CryptoKit and the Security framework export.
     * Throws [IllegalArgumentException] for anything that is not a point on P-256.
     */
    fun publicKeyOf(x963: ByteArray): ECPublicKey {
        require(x963.size == POINT_SIZE && x963[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, x963.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, x963.copyOfRange(1 + COORDINATE_SIZE, POINT_SIZE))
        require(isOnCurve(x, y)) { "not a point on P-256" }

        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    /** [plaintext] sealed for [recipient], in the format above. */
    fun seal(plaintext: ByteArray, recipient: ECPublicKey): ByteArray {
        val ephemeral = KeyPairGenerator.getInstance("EC").apply { initialize(curve, random) }.generateKeyPair()
        val ephemeralPublic = (ephemeral.public as ECPublicKey).toX963()

        val secret = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private)
            doPhase(recipient, true)
            generateSecret()
        }
        val key = hkdfSha256(secret, info = INFO + ephemeralPublic + recipient.toX963(), length = 32)

        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            doFinal(plaintext)
        }

        return byteArrayOf(VERSION) + ephemeralPublic + nonce + ciphertext
    }

    fun ECPublicKey.toX963(): ByteArray =
        byteArrayOf(0x04) + w.affineX.toCoordinate() + w.affineY.toCoordinate()

    private fun BigInteger.toCoordinate(): ByteArray {
        // Unsigned and exactly 32 bytes: toByteArray() adds a sign byte or drops leading zeros.
        val bytes = toByteArray().takeLast(COORDINATE_SIZE).toByteArray()
        return ByteArray(COORDINATE_SIZE - bytes.size) + bytes
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (curve.curve.field as java.security.spec.ECFieldFp).p
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val left = y.modPow(BigInteger.TWO, p)
        val right = (x.pow(3) + curve.curve.a * x + curve.curve.b).mod(p)
        return left == right
    }

    /** RFC 5869 with an empty salt, which HMAC pads to the same all-zero key the RFC asks for. */
    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var block = ByteArray(0)
        val output = ArrayList<Byte>(length)
        var counter = 1
        while (output.size < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter++.toByte())
            block = mac.doFinal()
            output += block.asList()
        }
        return output.take(length).toByteArray()
    }
}
