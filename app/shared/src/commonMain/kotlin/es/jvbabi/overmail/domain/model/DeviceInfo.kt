package es.jvbabi.overmail.domain.model

/**
 * The device the app runs on, as the server records it with the session a sign-in creates.
 * [platform] is `android` or `ios`; the server picks the shape of the rest by it.
 */
data class DeviceInfo(
    val platform: String,
    val device: String,
    val manufacturer: String,
    val os: String,
)
