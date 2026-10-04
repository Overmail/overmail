package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

object Users : UuidTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val firstname = varchar("firstname", 255)
    val lastname = varchar("lastname", 255)
    /** A BCrypt hash. Null for an account without a password, which signs in with a mailed code. */
    val password = varchar("password", 255).nullable()
    /** The base32 TOTP secret of the authenticator app. Null while there is no second factor. */
    val totpSecret = varchar("totp_secret", 64).nullable()
    /** Whether the sign-in offers a mailed code instead of the authenticator app's. */
    val emailOtpActive = bool("email_otp_active").default(true)
}

class User(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<User>(Users)
    typealias Id = Uuid

    var username by Users.username
    var password by Users.password
    var totpSecret by Users.totpSecret
    var emailOtpActive by Users.emailOtpActive
    var email by Users.email
    var firstname by Users.firstname
    var lastname by Users.lastname
}
