package es.jvbabi.overmail.server.http.share

import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/*
 * What a share link hands out, and to whom.
 *
 * Everything here answers requests with no session behind them: whoever holds the link is the
 * whole authorization, so nothing reads the caller and nothing about the owner is in the answer.
 * The two routes that use it are `getShare` (what is visible without a password) and `openShare`
 * (the same with the password typed in).
 */

/** One shared mail as both routes answer it. */
@Serializable
data class SharedEmailResponse(
    /** Whether the mail itself is behind a password the visitor has not typed yet. */
    @SerialName("needs_password") val needsPassword: Boolean,
    /**
     * Who wrote it, when, and about what.
     *
     * Null where the share keeps even that behind its password -- the page then has nothing to
     * show but the password field, which is the point of that setting.
     */
    @SerialName("metadata") val metadata: Metadata?,
    /** The mail itself. Only ever here once the share is open. */
    @SerialName("content") val content: Content?,
) {
    @Serializable
    data class Metadata(
        @SerialName("subject") val subject: String,
        /** Display name from this mail's header, absent for a bare address. */
        @SerialName("sender_name") val senderName: String?,
        @SerialName("sender_address") val senderAddress: String,
        /** Whole seconds since the epoch, like the rest of the mail api. */
        @SerialName("sent") val sent: Long,
        /** Only where the share was made with them; empty otherwise. */
        @SerialName("labels") val labels: List<Label>,
    )

    @Serializable
    data class Label(
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
    )

    @Serializable
    data class Content(
        @SerialName("text") val text: String?,
        @SerialName("html") val html: String?,
    )
}

/** A share row as the public routes need it. The owner is not in here, and neither is the mail. */
internal data class SharedLink(
    val emailId: Uuid,
    val includeLabels: Boolean,
    val passwordHash: String?,
    val allowMetadataWithoutPassword: Boolean,
)

/**
 * The share `{shareId}` names, or the end of the request.
 *
 * 404 for an id that is not one and for one nobody ever had -- neither says anything about which
 * links exist. 410 for one that ran out, which is a different thing to a reader: the link was
 * real, and the owner can hand out another.
 *
 * The id is taken with or without its hyphens, because the url a reader sees carries the bare hex
 * (see `sharePath` in the web app).
 */
internal suspend fun ApplicationCall.requireLiveShareFromUrl(): SharedLink {
    val raw = parameters["shareId"]
        ?: error("this route has no {shareId}, so its share cannot be resolved")
    val id = Uuid.parseOrNull(raw)
        ?: runCatching { Uuid.parseHex(raw) }.getOrNull()
        ?: notFound("share", raw)

    val share = database().query {
        Shares.selectAll().where { Shares.id eq id }.singleOrNull()
    } ?: notFound("share", raw)

    val validUntil = share[Shares.validUntil]
    if (validUntil != null && validUntil <= Clock.System.now()) {
        throw ApiException(
            status = HttpStatusCode.Gone,
            code = ApiErrorCode.GONE,
            message = "This share has run out",
            details = mapOf("resource" to "share", "id" to id.toString()),
        )
    }

    return SharedLink(
        emailId = share[Shares.email].value,
        includeLabels = share[Shares.includeLabels],
        passwordHash = share[Shares.passwordHash],
        allowMetadataWithoutPassword = share[Shares.allowMetadataWithoutPassword],
    )
}

/**
 * The mail behind [share], as much of it as [unlocked] allows.
 *
 * Locked, the answer carries the metadata or nothing at all, and never the body: what a visitor
 * has not typed the password for must not be in the response for them to read in the network tab.
 */
internal suspend fun ApplicationCall.readSharedEmail(share: SharedLink, unlocked: Boolean): SharedEmailResponse {
    val showMetadata = unlocked || share.allowMetadataWithoutPassword
    if (!showMetadata) return SharedEmailResponse(needsPassword = true, metadata = null, content = null)

    return database().query {
        val row = (Emails innerJoin EmailUsers)
            .select(Emails.subject, Emails.senderName, Emails.sent, Emails.textContent, Emails.htmlContent, EmailUsers.address)
            .where { Emails.id eq share.emailId }
            .singleOrNull()
            // The mail is gone but the link is not: a cascade takes the share with the mail, so
            // this is a race rather than a state, and the reader sees the same 404 either way.
            ?: notFound("share", share.emailId.toString())

        val labels = if (!share.includeLabels) emptyList() else (EmailLabels innerJoin Labels)
            .select(Labels.name, Labels.color)
            .where { EmailLabels.email eq share.emailId }
            .map { SharedEmailResponse.Label(name = it[Labels.name], color = it[Labels.color]) }

        SharedEmailResponse(
            needsPassword = !unlocked,
            metadata = SharedEmailResponse.Metadata(
                subject = row[Emails.subject],
                senderName = row[Emails.senderName],
                senderAddress = row[EmailUsers.address],
                sent = row[Emails.sent].epochSeconds,
                labels = labels,
            ),
            content = if (!unlocked) null else SharedEmailResponse.Content(
                text = row[Emails.textContent],
                html = row[Emails.htmlContent],
            ),
        )
    }
}
