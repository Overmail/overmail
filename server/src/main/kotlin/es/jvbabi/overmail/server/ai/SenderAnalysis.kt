package es.jvbabi.overmail.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a mail comes from: the person who wrote it, the organisation they wrote for, the platform
 * it came through if it came through one, and what it belongs to.
 *
 * Every field is empty-able and every one is meant to be: plenty of mail comes from a person with
 * no company behind them, at least as much from a company with no person in front of it, and most
 * of it from neither by way of a platform. A field nobody can fill from the mail is null, or an
 * empty list, which is an answer.
 */
@Serializable
data class SenderAnalysis(
    /**
     * The person, as the mail spells their name: "Julius Babies". Null for mail nobody signed --
     * a newsletter, a receipt, anything from `no-reply@`.
     */
    @SerialName("person") val person: String? = null,

    /**
     * The organisation the mail was written for: "Deutsche Bahn", not "bahn.de". Null for private
     * mail, and for a company that is only visible in the address.
     */
    @SerialName("organisation") val organisation: String? = null,

    /**
     * The platform the exchange plays out on, where the mail is a platform's notice about what
     * somebody else did: "GitHub" for a mail about a pull request, "LinkedIn" for a message
     * somebody sent there. Null for mail that is simply mail.
     *
     * Not the same as [organisation], and the difference is who the mail is really from: a shop's
     * receipt is the shop's own mail and has no platform behind it, while a comment on an issue is
     * GitHub writing about what a person did. The platform is the third party -- it would be named
     * the same whoever the person and the company turned out to be.
     */
    @SerialName("via") val via: String? = null,

    /**
     * What this mail belongs to, as handles rather than as a description: the issue it is about,
     * the newsletter it is an instalment of, the order it concerns.
     *
     * A list, because one mail can belong to several things -- a digest covering three issues has
     * a handle each. Empty for a mail that belongs to nothing in particular, which is what a
     * person writing directly looks like.
     *
     * Where there is a format for a thing, the handle uses it: a GitHub issue or pull request is
     * `gh:owner/repo#412`, which is short enough to read and exact enough to match on later.
     * Everything else is the name the thing goes by, and where the mail shows nothing more
     * specific than the kind of mail it is, one bare word for that kind.
     */
    @SerialName("context") val context: List<String> = emptyList(),
)

/**
 * A tag a reading proposes, together with why the mail carries it.
 *
 * The same pair the tagging step answers with, see [TopicTag], minus the quote: there is nothing to
 * quote here. A tag off the sender reading is not read out of a sentence -- it is the name the
 * reading already found, and the reason is what that field of the reading means.
 */
data class ProposedTag(val name: String, val reason: String)

/**
 * The sender reading as tags to file the mail under.
 *
 * Where the mail comes from is the one thing about it that a reader looks for by name -- "everything
 * from the Sparkasse", "everything that came through GitHub" -- and it is exactly what the tagging
 * step is told to leave alone, because it is read here and reading it twice would only produce two
 * spellings of it. So it is filed from here.
 *
 * Three of the four fields become tags. The organisation and the platform are the ones that group:
 * a hundred mails share them, which is what a tag is for. The person groups less but is what a
 * reader asks for most directly, so they get one too.
 *
 * [SenderAnalysis.context] becomes a tag too, but cut down first. Its entries are handles, and a
 * handle names two things at once: the thing mail keeps coming about, and the one instance this mail
 * is about. `gh:acme/widgets#412` is the repository `acme/widgets` -- which will collect mail for
 * years and is exactly what a reader wants to file under -- and pull request 412, which will not.
 * So the instance is cut off and the thing is kept, see [asThingName]. What is left with a number
 * still in it is not a thing but one occurrence of one, and that belongs to the identifier instead,
 * see [TopicAnalysis.threadId].
 */
fun SenderAnalysis.asTags(): List<ProposedTag> {
    val proposed = buildList {
        organisation?.asTagName()?.let {
            add(ProposedTag(it, "Die Mail kommt von $it."))
        }
        via?.asTagName()?.let {
            add(ProposedTag(it, "Kam über $it."))
        }
        // After the two above, so that a person who shares their name with the company they write
        // for is dropped as the duplicate rather than dropping the company.
        person?.asTagName()?.let {
            add(ProposedTag(it, "Geschrieben von $it."))
        }
        for (handle in context) {
            handle.asThingName()?.let { add(ProposedTag(it, "Gehört zu $it.")) }
        }
    }

    // Ignoring case, because "GitHub" as the platform and "Github" as the context are one tag and
    // the tag store would make them one anyway -- with two rows on the mail pointing at it.
    return proposed.distinctBy { it.name.lowercase() }
}

/** The field as a tag name, or null where it is nothing a reader would file under. */
private fun String.asTagName(): String? = trim()
    .takeIf { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH && it.words() <= MAX_TAG_WORDS }

/**
 * A handle with the one instance cut off it, leaving the thing that keeps producing mail -- or null
 * where there was never anything but the instance.
 *
 * `gh:acme/widgets#412` is a tag worth having as `acme/widgets`: a repository collects mail for
 * years, and "everything about that repo" is a question a reader really asks. Pull request 412 is
 * not that question, and neither is `INC0043221`.
 *
 * Four cuts, in this order: the platform prefix a handle carries (`gh:`), the fragment behind a `#`,
 * a numbered tail (`PROJ-123` is project PROJ), and then whatever punctuation the cuts left hanging.
 * What survives with a digit still in it is refused: a name with a number in it is an occurrence of
 * something rather than the something, and there is a field for occurrences.
 */
private fun String.asThingName(): String? {
    val thing = trim()
        .replace(KIND_PREFIX, "")
        .substringBefore('#')
        .replace(NUMBERED_TAIL, "")
        .trim()
        .trim('/', '-', '.', ',')

    if (thing.any { it.isDigit() } || thing.any { it in HANDLE_MARKERS }) return null

    return thing.asTagName()
}

private const val MAX_TAG_LENGTH = 40

/** As the tagging step is held to: past three words it is a description. */
private const val MAX_TAG_WORDS = 3

/**
 * Markers that mean what is left is still a handle rather than a name. `/` is not among them: it is
 * how a repository is written, and `acme/widgets` is one of the better tags a mail can get.
 */
private val HANDLE_MARKERS = charArrayOf(':', '#', '@')

/** The platform a handle names itself by: `gh:`, `jira:`. Lowercase, short, and at the front. */
private val KIND_PREFIX = Regex("""^[a-z][a-z0-9]{1,9}:""")

/** A numbered tail: the `-123` of `PROJ-123`, the `/42` of a path that ends in an issue. */
private val NUMBERED_TAIL = Regex("""[-/]\d+$""")

private val WHITESPACE = Regex("""\s+""")

private fun String.words(): Int = trim().split(WHITESPACE).count { it.isNotEmpty() }

/**
 * The step that fills it in.
 *
 * First of the analysis steps, and the shape the ones after it follow: one question, one schema,
 * one model. What the mail is *about* belongs to [TOPIC_STEP], which is a judgement rather than a
 * reading and is treated as one -- what is read here is filed, what is decided there is offered.
 */
val SENDER_STEP = MailAnalysisStep(
    id = "sender",
    instructions = """
        Say where this mail comes from: the person who wrote it, the organisation they wrote it
        for, the platform it came through if it came through one, and what it belongs to.

        - `person`: a human being, named as one, spelled as the mail spells it: "Julius Babies".
          Take it from a signature under the text, from the way the mail signs off, or from a line
          naming the writer ("Thomas Krause hat Ihnen geschrieben", "Jane Doe commented"). Spell it
          as a person is named, not as they log in: "Jane Doe", never "jane-doe".
          The sender's display name is not evidence of a person by itself -- organisations put
          their own name there just as often, and that is the usual case for mail that is not from
          a colleague. A school, an office, a company, a shop or a team is never a person, however
          much the name reads like one: a name carrying a place ("Musterschule Dresden") or a legal
          form ("Kaffee GmbH") is an organisation. Not a role ("Support Team"), not a greeting of
          the recipient, and never a name assembled out of an address. Where the mail names no
          human, `person` is null and that name belongs to `organisation` alone.
        - `organisation`: the company, authority, club or shop the mail was written for, in the name
          it uses for itself: "Deutsche Bahn", not "bahn.de" and not "Deutsche Bahn AG Vertrieb".
          Take it from the signature, the imprint or the letterhead.
        - `via`: the platform the matter plays out on, when this mail is that platform telling the
          owner what somebody else did there: "GitHub" for a mail about an issue or a pull request,
          "GitLab", "Jira", "LinkedIn", "Xing", "Trello", "eBay", "Airbnb", "LernSax", "Moodle",
          "IServ". The name the platform uses for itself, not its domain.
          Read it off the sender's side and off the mail's own footer, never off the To line and
          never off the owner's address. That a mail was delivered to someone at a school or at a
          company says nothing about where it came from -- mail from a shop to a pupil's school
          address is the shop's mail, with no platform behind it at all.

        `via` is for a third party, and that is what separates it from `organisation`. The test is
        whether the name would stay the same if a different person or a different company were
        involved: a comment on a repository is GitHub writing about what somebody did, so `via` is
        "GitHub" whoever wrote the comment. A shop's own order confirmation is the shop's mail and
        nobody else's, so `via` is null and the shop is the `organisation`.

        Those names are examples, not a list to match against. Anything many organisations use to
        reach their people counts the same way: a school's learning platform or school cloud, a
        university's portal, an authority's service desk, a shop or ticketing system. A platform
        you do not know by name is still a platform, and the mail almost always says which one it
        is -- "you are receiving this because you are registered at X", "log in to X to answer",
        "do not reply to this mail, answer in X", a footer that is X's rather than the sender's.

        An organisation and a platform are two answers, never one name. A school writing to its
        pupils through a school platform is the school in `organisation` and the platform in
        `via`; something like "Gymnasium Musterstadt LernSax" is not a name anybody has, it is two
        names glued together. Split them, and put each in its own field.

        `via` is never the machinery that carried the mail. "SendGrid", "Mailchimp", "Amazon SES",
        "Postmark", "Mailgun", "Brevo" and the like send mail on behalf of whoever pays them; they
        say nothing about where the matter plays out, and a mail they carried is not about them.
        Neither is a mail server, a relay or a mailing list program.

        Leave a field null when the mail does not show it. Mail from a person with no organisation
        behind them, and mail from an organisation with nobody named in front of it, are both
        normal and both have one field filled. A platform's notice often has no organisation at all
        -- a person, a platform, and nothing in between is a complete answer.

        `context` is a list of handles on what this mail belongs to, most specific first. Several
        entries only when the mail really is about several things: a digest covering three issues
        gets a handle each.

        A handle names a thing that outlasts this one mail -- further mails belong to the same
        thing and carry the same handle. It is never a description of what this mail says. The
        quick test: if what you are about to write would serve as the subject line of this mail,
        it is a topic, not a handle.

        Work down these and stop at the first that fits.

        1. Where there is a format for the thing, use it. A GitHub issue or pull request is
          `gh:owner/repo#number`, e.g. `gh:acme/widgets#412`. All three parts have to come out of
          the mail; leave the entry out rather than filling a part in. A mail naming several of
          them -- a digest, a thread summary -- gets one entry each, and that is what the list is
          for.
        2. Otherwise the name the thing goes by, where the mail names one that passes the test
          above: the title of a mailing, the name of a board, the number of an order. Take the
          name only, without the instalment wrapped around it -- "Wollmilchsau Wochenpost #48:
          Neue Roestung" belongs to `Wollmilchsau Wochenpost`, because the mailing is the thing
          while the number and this week's topic belong to this one mail.
          What a single announcement is about is not such a name. A deadline, an offer, a
          programme for the coming year, new opening hours: those are topics, however lasting they
          sound, and they go to 3.
        3. One bare word for the kind of mail it is, or nothing at all.
          - `notification` where the mail plainly belongs to something but names no thing that
            outlasts it: a platform's notice about what somebody did, or a school, an office or a
            shop announcing one matter to everyone on its list.
          - `newsletter` for a mailing with no title of its own.
          - an empty list where the mail belongs to nothing whatsoever, and that is one case: a
            person writing to the owner directly.

        The bare word is the last resort and never an addition. A mail that yields
        `gh:acme/widgets#412` is already placed, so it does not also get `notification`, and a
        digest that yields two handles does not get one either.

        The mailbox owner is not the answer unless the mail is theirs -- read the direction.
    """.trimIndent(),
    serializer = SenderAnalysis.serializer(),
    tier = ModelTier.FAST,
    maxOutputTokens = 350,
    validate = { analysis, _ ->
        // A schema can say "string or null"; it cannot say "not the empty string", how long a list
        // may get, or what shape the strings in it have. A model that misses one of those is worth
        // one more ask with the miss named.
        when {
            analysis.person?.isBlank() == true -> "`person` came back empty. Use null instead."
            analysis.organisation?.isBlank() == true -> "`organisation` came back empty. Use null."
            analysis.via?.isBlank() == true -> "`via` came back empty. Use null instead."
            analysis.context.any { it.isBlank() } ->
                "`context` came back with an empty entry. Leave the entry out instead."
            analysis.context.size > MAX_CONTEXT ->
                "`context` came back with ${analysis.context.size} entries. Name at most " +
                    "$MAX_CONTEXT, and only what the mail actually shows."
            // The one format this step states outright, so the one worth holding the model to.
            else -> analysis.context
                .firstOrNull { it.startsWith(GITHUB_PREFIX) && !it.matches(GITHUB_HANDLE) }
                ?.let {
                    "`context` has \"$it\", which is not the `gh:owner/repo#number` format. " +
                        "Correct it, or leave the entry out if the mail does not show every part."
                }
        }
    },
)

/**
 * The most handles one mail is worth. A guard rather than a rule any real mail meets: a model
 * listing dozens has started inventing, and the ask is cheaper than the answer.
 */
private const val MAX_CONTEXT = 10

private const val GITHUB_PREFIX = "gh:"

/**
 * `gh:acme/widgets#412`, and `gh:acme/widgets` for a mail about the repository rather than about
 * anything in it. No whitespace in either part -- an owner or a repository with a space in it is a
 * sentence the model put there, not a name GitHub would accept.
 */
private val GITHUB_HANDLE = Regex("""^gh:[^/\s]+/[^#\s]+(#\d+)?$""")
