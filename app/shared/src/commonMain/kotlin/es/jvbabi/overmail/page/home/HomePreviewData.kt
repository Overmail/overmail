package es.jvbabi.overmail.page.home

import androidx.compose.ui.graphics.Color
import es.jvbabi.overmail.domain.model.ArchivedState
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.EmailRecipient
import es.jvbabi.overmail.domain.model.EmailRecipientType
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.ViewResult
import kotlinx.datetime.Month
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A mailbox full of made-up mail for the previews of the home screen, grouped like the mailbox
 * itself: today, yesterday, this week, this month, then by month.
 *
 * Seeded, so every render of a preview shows the same mails.
 */
internal val PREVIEW_VIEW_CONTENT: ViewContentState by lazy {
    ViewContentState(results = previewGroups(), isLoading = false)
}

private val PREVIEW_ACCOUNT = OvermailAccount(
    id = Uuid.fromLongs(0, 0),
    username = "preview",
    firstName = "Preview",
    lastName = "User",
    email = "preview@example.com",
    homeserver = "https://example.com",
    token = "",
)

private val PREVIEW_IMAP_ACCOUNTS = listOf("preview@example.com", "preview.work@example.org")
    .mapIndexed { index, username ->
        ImapAccount(
            id = Uuid.fromLongs(1, index.toLong()),
            host = "imap.example.com",
            port = 993,
            username = username,
            isPaused = false,
            emailCount = 0,
            overmailAccount = PREVIEW_ACCOUNT,
        )
    }

private val PREVIEW_SELF = participant(0, "Preview User", "preview@example.com")

private val PREVIEW_SENDERS = listOf(
    participant(1, "University of Waterloo", "uninews@uwaterloo.com"),
    participant(2, "Google Account", "account@google.com"),
    participant(3, null, "nsmith@hotmail.com"),
    participant(4, "GitHub", "noreply@github.com"),
    participant(5, "Deutsche Bahn", "buchungsbestaetigung@bahn.de"),
    participant(6, "Anna Schmidt", "anna.schmidt@example.de"),
    participant(7, "Lukas Weber", "l.weber@example.org"),
    participant(8, "Stripe", "receipts@stripe.com"),
    participant(9, "Figma", "team@figma.com"),
    participant(10, null, "billing@hetzner.com"),
    participant(11, "Maria García", "maria.garcia@example.es"),
    participant(12, "Linear", "notifications@linear.app"),
)

private val PREVIEW_LABELS = listOf(
    "Finance" to Color(0xFF2E7D32),
    "Travel" to Color(0xFF1565C0),
    "University" to Color(0xFF6A1B9A),
    "Work" to Color(0xFFEF6C00),
    "Newsletter" to Color(0xFF546E7A),
).mapIndexed { index, (name, color) ->
    Label(
        id = Uuid.fromLongs(3, index.toLong()),
        name = name,
        color = color,
        emailCount = 0,
        overmailAccount = PREVIEW_ACCOUNT,
    )
}

private val PREVIEW_SUBJECTS = listOf(
    "Q2 Budget report",
    "Your booking confirmation for Berlin Hbf → München Hbf",
    "Security alert: new sign-in on Android",
    "[overmail] Pull request #42: Add view settings",
    "Invoice #2026-0913",
    "Re: Dinner on Saturday?",
    "Your receipt from Stripe",
    "Weekly digest: 12 new comments",
    "Exam schedule for the winter term",
    "Fwd: Slides from yesterday's meeting",
    "Your server invoice for September",
    "Reminder: dentist appointment tomorrow",
    "New login to your account",
    "Re: Re: Project timeline",
    "Welcome to the team!",
    "Your package is on its way",
)

/** The mailbox's stretches and how many mails each holds, newest first. */
private fun previewGroups(): List<ViewResult.Group> {
    val random = Random(42)
    val now = Clock.System.now()
    var next = 0L

    fun emails(count: Int, newest: Instant, spacing: kotlin.time.Duration): List<ViewResult.Item> =
        List(count) { index -> ViewResult.Item(previewEmail(next++, newest - spacing * index, random)) }

    return listOf(
        ViewResult.Group.DateSmart(ViewResult.Group.DateSmart.Stretch.Today, emails(6, now - 12.minutes, 47.minutes)),
        ViewResult.Group.DateSmart(ViewResult.Group.DateSmart.Stretch.Yesterday, emails(5, now - 1.days, 2.hours)),
        ViewResult.Group.DateSmart(ViewResult.Group.DateSmart.Stretch.Week, emails(9, now - 2.days, 7.hours)),
        ViewResult.Group.DateSmart(ViewResult.Group.DateSmart.Stretch.Month, emails(12, now - 6.days, 13.hours)),
        ViewResult.Group.DateSmart(
            ViewResult.Group.DateSmart.Stretch.CalendarMonth(2026, Month.AUGUST),
            emails(14, now - 30.days, 2.days),
        ),
        ViewResult.Group.DateSmart(
            ViewResult.Group.DateSmart.Stretch.CalendarMonth(2026, Month.JULY),
            emails(11, now - 60.days, 3.days),
        ),
        ViewResult.Group.DateSmart(
            ViewResult.Group.DateSmart.Stretch.CalendarMonth(2026, Month.JUNE),
            emails(4, now - 95.days, 6.days),
        ),
    )
}

private fun previewEmail(index: Long, sentAt: Instant, random: Random): Email {
    val sender = PREVIEW_SENDERS[random.nextInt(PREVIEW_SENDERS.size)]
    val cc = PREVIEW_SENDERS.filter { it != sender }.shuffled(random).take(random.nextInt(0, 3))

    return Email(
        id = Uuid.fromLongs(4, index),
        overmailAccount = PREVIEW_ACCOUNT,
        imapAccount = PREVIEW_IMAP_ACCOUNTS[random.nextInt(PREVIEW_IMAP_ACCOUNTS.size)],
        sentBy = sender,
        sentAt = sentAt,
        subject = PREVIEW_SUBJECTS[random.nextInt(PREVIEW_SUBJECTS.size)].takeIf { random.nextInt(20) != 0 },
        // The newest ones are the ones still unread.
        isRead = index > 4 && random.nextInt(4) != 0,
        archivedState = ArchivedState.Unarchive,
        labels = PREVIEW_LABELS.shuffled(random).take(random.nextInt(0, 3)),
        recipients = listOf(EmailRecipient(PREVIEW_SELF, EmailRecipientType.Recipient)) +
            cc.map { EmailRecipient(it, EmailRecipientType.Cc) },
    )
}

private fun participant(index: Long, name: String?, email: String) = Participant(
    id = Uuid.fromLongs(2, index),
    name = name,
    email = email,
    avatarUrl = null,
    avatarPadding = null,
    emailCount = 0,
    overmailAccount = PREVIEW_ACCOUNT,
)
