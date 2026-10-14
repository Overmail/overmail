package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.jobs.push.ApplePushSealing.toX963
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A device's key pair, as the app makes it. */
fun deviceKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

fun KeyPair.publicX963Base64(): String = Base64.getEncoder().encodeToString((public as ECPublicKey).toX963())

fun iPhone(token: String?, keys: KeyPair = deviceKeys()) = Session.Client.Ios(
    device = "iPhone",
    os = "iOS 26",
    applePush = token?.let {
        Session.Client.ApplePushRegistration(it, Session.Client.ApnsEnvironment.Production, keys.publicX963Base64())
    },
)

/**
 * Opens what [ApplePushSealing.seal] sealed, the way the notification service extension does --
 * written out step by step from the format rather than with the sealing code, so a test catches a
 * change to either side.
 */
fun openSealed(sealed: ByteArray, keys: KeyPair): ByteArray {
    require(sealed[0] == 1.toByte()) { "unknown version ${sealed[0]}" }
    val ephemeral = sealed.copyOfRange(1, 66)
    val nonce = sealed.copyOfRange(66, 78)
    val ciphertext = sealed.copyOfRange(78, sealed.size)

    val secret = KeyAgreement.getInstance("ECDH").run {
        init(keys.private)
        doPhase(ApplePushSealing.publicKeyOf(ephemeral), true)
        generateSecret()
    }
    val info = "overmail-push-v1".encodeToByteArray() + ephemeral + (keys.public as ECPublicKey).toX963()
    val prk = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        doFinal(secret)
    }
    val key = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(prk, "HmacSHA256"))
        doFinal(info + byteArrayOf(1))
    }

    return Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        doFinal(ciphertext)
    }
}
