<script module lang="ts">
    /** One formatter for the screen, as on the stack: building one costs more than using it. */
    const SENT_FORMAT = new Intl.DateTimeFormat(undefined, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });
</script>

<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import * as Empty from "$lib/components/ui/empty";
    import {Separator} from "$lib/components/ui/separator";
    import {Badge} from "$lib/components/ui/badge";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import {EmailDetailStore} from "$lib/app/email/EmailDetailStore.svelte";
    import {avatarStore} from "$lib/app/avatars/AvatarStore.svelte";
    import type {MailParticipant} from "$lib/repository/MailRepository";
    import {page} from "$app/state";
    import {ArrowLeftIcon, TrayIcon, WarningIcon} from "phosphor-svelte";

    const id = $derived(page.params.id ?? "");

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

    const cardSender = $derived(
        mail && {
            ...toCardParticipant(mail.sender),
            // Absent for an address no picture was found for; the card falls back to initials.
            avatarUrl: avatarStore.urlFor(mail.sender.address) ?? undefined,
        },
    );

    /**
     * What the agent made of the sender, as the line under the heading. Null while it is still
     * reading; a run that came back with neither name says so rather than showing an empty line.
     */
    const senderReading = $derived.by(() => {
        if (!sender) return null;
        if (sender.failure) return "Der Agent konnte die Mail nicht lesen.";

        const named = [sender.person, sender.organisation].filter(Boolean);
        return named.length ? named.join(" · ") : "Kein Name in der Mail.";
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
        <div class="mx-auto flex w-full max-w-5xl flex-col gap-4 p-6">
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
                    tags={mail.tags.map((tag) => tag.name)}
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

                <dt class="text-muted-foreground">Tags</dt>
                <dd>
                    {#if mail.tags.length}
                        {mail.tags.map((tag) => tag.name).join(", ")}
                    {:else}
                        <span class="text-muted-foreground">Keine</span>
                    {/if}
                </dd>

                <!-- What the agent read out of the mail. Its own row rather than a note on the
                     card: it is a reading of the mail, not something the mail says. -->
                <dt class="text-muted-foreground">Absender</dt>
                <dd>
                    {#if senderReading}
                        {#if sender?.person}{sender.person}{/if}
                        {#if sender?.person && sender?.organisation}
                            <span class="text-muted-foreground">·</span>
                        {/if}
                        {#if sender?.organisation}{sender.organisation}{/if}
                        {#if !sender?.person && !sender?.organisation}
                            <span class="text-muted-foreground">{senderReading}</span>
                        {/if}
                    {:else}
                        <span class="text-muted-foreground">Der Agent liest die Mail …</span>
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
    {/if}
</main>
