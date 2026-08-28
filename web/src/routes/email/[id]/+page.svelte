<script module lang="ts">
    /** One formatter for the screen, as on the stack: building one costs more than using it. */
    const SENT_FORMAT = new Intl.DateTimeFormat(undefined, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });

    /**
     * What the agent's ways into an account are called on screen. Its own map rather than the wire
     * word: the schema is written for a model and the screen is read by a person.
     */
    const MAGIC_KINDS: Record<string, string> = {
        code: 'Code',
        link: 'Anmeldelink'
    };

    /**
     * The same for the kind of thing an identifier identifies -- what a matter is called on screen.
     * Two of them land on the same word on purpose: a reader looking at "Ticket INC0043221" does not
     * care whether the platform calls it an issue or a case.
     */
    const IDENTIFIER_KINDS: Record<string, string> = {
        invoice: 'Rechnung',
        order: 'Bestellung',
        booking: 'Buchung',
        shipment: 'Sendung',
        ticket: 'Ticket',
        transaction: 'Zahlung',
        issue: 'Ticket',
        conversation: 'Unterhaltung',
        other: 'Vorgang'
    };
</script>

<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import * as Empty from "$lib/components/ui/empty";
    import {Separator} from "$lib/components/ui/separator";
    import {Badge} from "$lib/components/ui/badge";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import AgentLog from "$lib/app/email/AgentLog.svelte";
    import {EmailDetailStore} from "$lib/app/email/EmailDetailStore.svelte";
    import {avatarStore} from "$lib/app/avatars/AvatarStore.svelte";
    import type {MailParticipant} from "$lib/repository/MailRepository";
    import {mailIdFromParam, mailPath} from "$lib/app/mails/mailUrl";
    import {replaceState} from "$app/navigation";
    import {page} from "$app/state";
    import {ArrowLeftIcon, TrayIcon, WarningIcon} from "phosphor-svelte";

    // The path may carry the subject in front of the id so a link says which mail it is; only the
    // id here means anything, everything before it is dropped. See `mailPath`.
    const id = $derived(mailIdFromParam(page.params.id ?? ""));

    // One store per mail: the id is what it watches, so a different id is a different store rather
    // than a store told to look elsewhere.
    const store = $derived(new EmailDetailStore(id));

    $effect(() => {
        store.open();
        return () => store.close();
    });

    // One list of pictures for the whole mailbox, asked for here as on every screen that shows one.
    $effect(() => avatarStore.ensureLoaded());

    const mail = $derived(store.mail);
    const spam = $derived(store.spam);
    const sender = $derived(store.sender);
    const magic = $derived(store.magic);
    const topic = $derived(store.topic);
    const revision = $derived(store.revision);

    /**
     * What the last run changed about the mailbox, and a note where it changed nothing.
     *
     * The changes are shown even when the step was cut off: the tools change things as they are
     * called, so what it got round to doing stands, and a reader who sees a tag move deserves to
     * find it here rather than to wonder.
     */
    const revisionReading = $derived.by(() => {
        if (!revision) return null;

        const changes = revision.changes ?? [];

        if (revision.failure) {
            return {changes, note: "Abgebrochen — was schon geändert wurde, bleibt."};
        }
        if (revision.ran === false) {
            return {changes, note: "Nichts zu vergleichen: keine Tags und keine Nummer."};
        }
        if (revision.proposals_filed_as_proposed) {
            return {changes, note: "Vorschläge unverändert übernommen — nicht mit dem Postfach abgeglichen."};
        }

        return {changes, note: changes.length ? null : (revision.said ?? "Nichts geändert.")};
    });

    /**
     * What the mail is filed under, with the reason it was filed for on hover.
     *
     * Read off the mail rather than off the run: the reason is stored with the filing, so it is
     * still there tomorrow and it is the reason that actually applies -- the agent may well have
     * filed a label the mailbox already had instead of the one it first thought of. A tag a reader
     * attached themselves has no reason, and needs none.
     */
    const mailTags = $derived(
        (mail?.tags ?? []).map((tag) => ({
            name: tag.name,
            byAgent: tag.by_agent ?? false,
            why: tag.reason ?? undefined,
        })),
    );

    /**
     * What the last run proposed but has not filed, where any of it is still not on the mail.
     *
     * Normally empty: the revision step files what it agrees with, under the mailbox's own words. A
     * proposal that shows up here is one it dropped or renamed, which is worth seeing -- it is the
     * one place the two steps visibly disagree.
     */
    const droppedProposals = $derived(
        (topic?.tags ?? []).filter(
            (proposal) =>
                !mailTags.some((tag) => tag.name.toLowerCase() === proposal.tag.toLowerCase()),
        ),
    );

    /**
     * The matter this mail belongs to as the agent read it: the identifier and what kind of thing it
     * identifies. Null until a run has finished the step, and null for the mail that carries none --
     * which is most of it.
     */
    const identifierReading = $derived.by(() => {
        if (!topic) return null;

        if (topic.failure) return {what: null, note: "Der Agent konnte die Mail nicht lesen."};
        if (!topic.identifier) return {what: null, note: "Keine Nummer, die etwas eindeutig macht."};

        const kind = topic.identifier_kind ? IDENTIFIER_KINDS[topic.identifier_kind] : undefined;

        return {
            what: kind ? `${kind} ${topic.identifier}` : topic.identifier,
            // A matter gets its stack from the second mail about it, not from the first: a stack of
            // one mail is a second listing of that mail. Said out loud, because "Vorgang: Rechnung
            // R00123" next to "Thread: In keinem Thread" otherwise reads as something gone wrong.
            note:
                topic.matter === "noted"
                    ? "Erste Mail dazu — ein Stapel entsteht ab der zweiten."
                    : null,
        };
    });

    const cardSender = $derived(
        mail && {
            ...toCardParticipant(mail.sender),
            // Absent for an address no picture was found for; the card falls back to initials.
            avatarUrl: avatarStore.urlFor(mail.sender.address) ?? undefined,
        },
    );

    /** What the agent placed the mail under, empty until it has read it or when it found nothing. */
    const senderContext = $derived(sender?.context ?? []);

    /**
     * Why there is no reading yet. The agent does not run on its own, so "not yet" is two different
     * states: nobody has asked, or somebody has and the model is still going.
     */
    const agentNote = $derived(
        store.analysing ? "Der Agent liest die Mail …" : "Noch nicht gelesen",
    );

    /**
     * What the agent made of the sender. Null until a run has come back with one, and otherwise
     * always the same three slots, so the markup below has nothing left to work out: the names as one line,
     * the platform apart from them because it is not one of them, and a note for the runs that
     * came back with nothing to show.
     */
    const senderReading = $derived.by(() => {
        if (!sender) return null;

        if (sender.failure) {
            return {names: null, via: null, note: "Der Agent konnte die Mail nicht lesen."};
        }

        const names = [sender.person, sender.organisation].filter(Boolean).join(" · ");

        return {
            names: names || null,
            via: sender.via ?? null,
            // A platform with nobody named in front of it is still a reading, so the note is only
            // for the case where there is neither.
            note: names || sender.via ? null : "Kein Name in der Mail."
        };
    });

    /**
     * What the mail lets the reader into, in the same three-slot shape as the sender reading: what
     * it is and who it belongs to, until when, and a note for the runs that found nothing.
     *
     * Nothing to accept here, unlike the tags the agent used to offer: the row is written the
     * moment the agent reads it, so this only says what landed in the table.
     */
    const magicReading = $derived.by(() => {
        if (!magic) return null;

        if (magic.failure) {
            return {what: null, validUntil: null, note: "Der Agent konnte die Mail nicht lesen."};
        }

        const kinds = (magic.kinds ?? []).map((kind) => MAGIC_KINDS[kind] ?? kind);

        // A provider with nothing to use it with is not a way in, and the server does not send one
        // -- so the kinds alone decide whether there is anything to show.
        if (!kinds.length) {
            return {what: null, validUntil: null, note: "Kein Code und kein Anmeldelink."};
        }

        return {
            what: magic.provider ? `${kinds.join(" · ")} für ${magic.provider}` : kinds.join(" · "),
            validUntil: magic.valid_until ?? null,
            note: null
        };
    });

    function toCardParticipant(participant: MailParticipant) {
        return {name: participant.name ?? undefined, address: participant.address};
    }

    /** The states a mail can be in, as the badges that say so. Only what is true is shown. */
    const badges = $derived([
        !mail?.is_read && {label: "Ungelesen", variant: "default" as const},
        mail?.is_archived && {label: "Archiviert", variant: "secondary" as const},
        spam?.is_spam && {
            label: spam.filter ? `Spam durch „${spam.filter.name}“` : "Spam",
            variant: "destructive" as const,
        },
    ].filter((badge): badge is {label: string; variant: "default" | "secondary" | "destructive"} => !!badge));

    /** What the screen is doing, for the one case worth saying out loud. */
    const isStale = $derived(store.status === "offline");

    /**
     * The mail whose subject the path already carries. Kept out of the effect's reading, so that
     * writing the path does not have the effect run again on the change it made itself.
     */
    let titled: string | null = null;

    /**
     * Writes the subject in front of the id once the mail is here, so the address says which mail
     * this is -- in a shared link, in the history, on a tab.
     *
     * Here rather than at the links that lead here: the subject is not on the link, it arrives with
     * the mail. Replaced rather than pushed, and shallow: one mail is one history entry, and a
     * navigation would tear this screen down and open the socket a second time.
     */
    $effect(() => {
        const subject = mail?.subject;
        if (!subject || titled === id) return;

        titled = id;
        const path = mailPath(id, subject);
        if (path !== page.url.pathname) replaceState(path, page.state);
    });
</script>

<header class="flex shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear h-12">
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />

        <Button variant="ghost" size="sm" href="/" class="-ms-1">
            <ArrowLeftIcon />
            Mailbox
        </Button>

        <h1 class="truncate text-base font-medium">{mail?.subject ?? "E-Mail"}</h1>

        <div class="ms-auto flex items-center gap-2">
            <!-- Said only while it is true: a screen that is current has nothing to report about
                 itself, and the mail below is what the reader came for. -->
            {#if isStale}
                <span class="text-muted-foreground text-sm">Verbindung verloren, neuer Versuch …</span>
            {/if}
        </div>
    </div>
</header>

<main class="flex flex-1 flex-col overflow-y-auto">
    {#if store.status === "gone"}
        <div class="flex flex-1 items-center justify-center px-8">
            <Empty.Root>
                <Empty.Header>
                    <Empty.Media variant="icon">
                        <TrayIcon />
                    </Empty.Media>
                    <Empty.Title>Diese E-Mail gibt es nicht</Empty.Title>
                    <Empty.Description>
                        Sie wurde gelöscht, oder der Link gehört zu einem anderen Postfach.
                    </Empty.Description>
                </Empty.Header>
            </Empty.Root>
        </div>
    {:else if !mail || !cardSender}
        <!-- The shape of the card rather than a spinner: what arrives fills it in. -->
        <div class="mx-auto flex w-full max-w-5xl flex-col gap-4 p-6">
            <Skeleton class="h-12 w-64" />
            <Skeleton class="h-72 w-full" />
        </div>
    {:else}
        <!-- The mail on one side, the agent on the other. Two columns from a desktop width on;
             below that the log goes under the mail, because neither is readable at half a phone. -->
        <div class="mx-auto grid w-full max-w-7xl gap-4 p-6 lg:grid-cols-[minmax(0,1fr)_28rem] lg:items-start">
            <div class="flex min-w-0 flex-col gap-4">
                {#if badges.length}
                    <div class="flex flex-row flex-wrap items-center gap-2">
                        {#each badges as badge (badge.label)}
                            <Badge variant={badge.variant}>
                                {#if badge.variant === "destructive"}<WarningIcon />{/if}
                                {badge.label}
                            </Badge>
                        {/each}
                    </div>
                {/if}

                <EmailCard
                        sender={cardSender}
                        sent={SENT_FORMAT.format(new Date(mail.sent_at))}
                        to={mail.recipients.map(toCardParticipant)}
                        cc={mail.cc.map(toCardParticipant)}
                        bcc={mail.bcc.map(toCardParticipant)}
                        subject={mail.subject}
                        tags={mail.tags.map((tag) => ({name: tag.name, byAgent: tag.by_agent}))}
                        body={store.body}
                        class="w-full shadow-none ring-1 ring-border"
                />

                <!-- What the card does not carry: where the mail is filed, and when it became spam.
                     Live like everything else here -- the socket sends the whole state on every
                     change, so this is never one refresh behind the badges above. -->
                <dl class="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-2xl border p-6 text-sm">
                    <dt class="text-muted-foreground">Empfangen</dt>
                    <dd>{SENT_FORMAT.format(new Date(mail.sent_at))}</dd>

                    <dt class="text-muted-foreground">Thread</dt>
                    <dd>
                        {#if mail.thread}
                            {mail.thread.title}
                            <span class="text-muted-foreground">({mail.thread.size} E-Mails)</span>
                        {:else}
                            <span class="text-muted-foreground">In keinem Thread</span>
                        {/if}
                    </dd>

                    <!-- What the mail is filed under. Chips rather than a line of text, because
                         each one carries the agent's reason on hover where the agent put it there --
                         a tag a reader can check is a tag they can disagree with. -->
                    <dt class="text-muted-foreground">Tags</dt>
                    <dd>
                        {#if mailTags.length || droppedProposals.length}
                            <div class="flex flex-row flex-wrap items-center gap-1">
                                {#each mailTags as tag (tag.name)}
                                    <Badge variant="secondary" title={tag.why}>{tag.name}</Badge>
                                {/each}
                                <!-- Outlined and struck through: proposed by the reading step and
                                     not filed by the one that checked it against the mailbox. -->
                                {#each droppedProposals as proposal (proposal.tag)}
                                    <Badge
                                            variant="outline"
                                            class="text-muted-foreground line-through"
                                            title={`Vorgeschlagen, nicht übernommen: ${proposal.reason}`}
                                    >{proposal.tag}</Badge>
                                {/each}
                            </div>
                        {:else if topic}
                            <span class="text-muted-foreground">Keine</span>
                        {:else}
                            <span class="text-muted-foreground">{agentNote}</span>
                        {/if}
                    </dd>

                    <!-- The number the matter goes by, where the mail carries one. Its own row next
                         to the thread above: the thread is where the mail landed, this is what put
                         it there. -->
                    <dt class="text-muted-foreground">Vorgang</dt>
                    <dd>
                        {#if identifierReading}
                            {#if identifierReading.what}
                                <span class="font-mono">{identifierReading.what}</span>
                            {/if}
                            {#if identifierReading.note}
                                <span class="text-muted-foreground">{identifierReading.note}</span>
                            {/if}
                        {:else}
                            <span class="text-muted-foreground">{agentNote}</span>
                        {/if}
                    </dd>

                    <!-- What the agent read out of the mail. Its own row rather than a note on the
                         card: it is a reading of the mail, not something the mail says. -->
                    <dt class="text-muted-foreground">Absender</dt>
                    <dd>
                        {#if senderReading}
                            {#if senderReading.names}{senderReading.names}{/if}
                            <!-- Muted and behind the names: the platform is how the mail got here,
                                 not who it is from. -->
                            {#if senderReading.via}
                                <span class="text-muted-foreground">über {senderReading.via}</span>
                            {/if}
                            {#if senderReading.note}
                                <span class="text-muted-foreground">{senderReading.note}</span>
                            {/if}
                        {:else}
                            <span class="text-muted-foreground">{agentNote}</span>
                        {/if}
                    </dd>

                    <!-- What the mail is good for, where it is one of those mails that exist to
                         let you in somewhere. Stated rather than offered: the server writes the row
                         as it reads it, because a code in a mail is a fact about the mail and not
                         an opinion about it. -->
                    <dt class="text-muted-foreground">Zugang</dt>
                    <dd>
                        {#if magicReading}
                            {#if magicReading.what}{magicReading.what}{/if}
                            <!-- Only where the mail said so itself: most of these never do, and a
                                 made-up deadline is worse than none. -->
                            {#if magicReading.validUntil}
                                <span class="text-muted-foreground">
                                    bis {SENT_FORMAT.format(new Date(magicReading.validUntil))}
                                </span>
                            {/if}
                            {#if magicReading.note}
                                <span class="text-muted-foreground">{magicReading.note}</span>
                            {/if}
                        {:else}
                            <span class="text-muted-foreground">{agentNote}</span>
                        {/if}
                    </dd>

                    <!-- What the mail belongs to, as the agent's own handles on it. Chips rather
                         than a sentence: each one is a thing to match on later, not prose. -->
                    <dt class="text-muted-foreground">Kontext</dt>
                    <dd>
                        {#if senderContext.length}
                            <div class="flex flex-row flex-wrap items-center gap-1">
                                {#each senderContext as handle (handle)}
                                    <Badge variant="outline" class="font-mono">{handle}</Badge>
                                {/each}
                            </div>
                        {:else if sender}
                            <span class="text-muted-foreground">Nichts Bestimmtes</span>
                        {:else}
                            <span class="text-muted-foreground">{agentNote}</span>
                        {/if}
                    </dd>

                    <!-- What the agent changed after looking at the mail that came before this
                         one. Its own row because it is the only thing here that is not about this
                         mail alone: the lines can just as well be about a mail from March. -->
                    <dt class="text-muted-foreground">Revision</dt>
                    <dd>
                        {#if revisionReading}
                            {#if revisionReading.changes.length}
                                <ul class="flex flex-col gap-1">
                                    {#each revisionReading.changes as change (change)}
                                        <li>{change}</li>
                                    {/each}
                                </ul>
                            {/if}
                            {#if revisionReading.note}
                                <span class="text-muted-foreground">{revisionReading.note}</span>
                            {/if}
                        {:else}
                            <span class="text-muted-foreground">{agentNote}</span>
                        {/if}
                    </dd>

                    <dt class="text-muted-foreground">Spam</dt>
                    <dd>
                        {#if spam?.is_spam}
                            {spam.filter ? `Vom Filter „${spam.filter.name}“ einsortiert` : "Von Hand einsortiert"}
                            {#if spam.changed_at}
                                <span class="text-muted-foreground">
                                    am {SENT_FORMAT.format(new Date(spam.changed_at))}
                                </span>
                            {/if}
                        {:else}
                            <span class="text-muted-foreground">Nein</span>
                        {/if}
                    </dd>

                    <dt class="text-muted-foreground">ID</dt>
                    <dd class="font-mono text-xs wrap-anywhere">{mail.id}</dd>
                </dl>
            </div>

            <!-- The whole conversation behind the reading above. Here for testing: it is how you
                 tell a prompt that is wrong from a model that is.

                 Stuck to the top of its column, so scrolling through a long mail keeps the log in
                 view rather than leaving it behind at the top of the page. -->
            <div class="min-w-0 lg:sticky lg:top-6">
                <AgentLog
                        log={store.log}
                        analysing={store.analysing}
                        onStart={() => store.readMail()}
                />
            </div>
        </div>
    {/if}
</main>
