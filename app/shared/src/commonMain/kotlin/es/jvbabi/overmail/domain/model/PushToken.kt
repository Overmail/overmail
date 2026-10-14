package es.jvbabi.overmail.domain.model

/** Where a push for this installation goes, as the platform's push service addresses it. */
sealed class PushToken {

    /** Android: the Firebase Installation ID, which Firebase Cloud Messaging sends to. */
    data class Firebase(val installationId: String) : PushToken()

    /**
     * iOS: the APNs device [token] in hex, whether the build talks to the [isSandbox] APNs, and
     * the device's P-256 [publicKey] (X9.63, base64) that what a notification shows is sealed for.
     */
    data class Apple(val token: String, val isSandbox: Boolean, val publicKey: String) : PushToken()
}
