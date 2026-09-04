<!--
    A mail as a card: how it begins, and then what it is -- subject, who sent it and when, and the
    labels it carries. The body is only the first stretch of the mail; the magnifier over it is
    there to read that stretch without opening the mail.
-->
<script lang="ts" module>
    import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";

    type EmailBody = {text: string | null; html: string | null};

    const bodyRepository = new EmailBodyRepository();

    /** Bodies that are already here. A mail shown a second time has nothing to wait for. */
    const loaded = new Map<string, EmailBody>();

    /** Requests in flight, so however many cards show one mail ask for it once. */
    const pending = new Map<string, Promise<EmailBody>>();

    function loadBody(id: string): Promise<EmailBody> {
        let request = pending.get(id);
        if (!request) {
            // A body we cannot fetch is no body as far as the card is concerned -- it shows the
            // mail it has rather than an error where the mail should be.
            request = bodyRepository.getBody(id)
                .catch(() => ({text: null, html: null}))
                .then((body) => {
                    loaded.set(id, body);
                    pending.delete(id);
                    return body;
                });
            pending.set(id, request);
        }
        return request;
    }
</script>

<script lang="ts">
    import EmailBodyPreview from "$lib/app/mails/EmailBodyPreview.svelte";
    import MailLabelBadges from "$lib/app/mails/table/MailLabelBadges.svelte";
    import {emailPath} from "$lib/app/mails/emailPath";
    import {displayName} from "$lib/app/mails/participants";
    import {OvermailCircularAvatar} from "$lib/components/avatar";
    import RelativeTime from "$lib/components/time/RelativeTime.svelte";
    import {Spinner} from "$lib/components/ui/spinner";
    import {cn} from "$lib/utils";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {_} from "svelte-i18n";
    import {fade} from "svelte/transition";

    /** How long a body that had to be fetched takes to arrive on the card. */
    const CONTENT_FADE_MS = 250;

    let {
        mail,
        class: className,
    }: {
        mail: EmailMeta;
        /** Where the card sits and how wide it is; the card brings its own height. */
        class?: string;
    } = $props();

    // Read out here rather than in the effect below: a body that is already here is on the card
    // in its first frame, instead of a spinner for one. The initial value is the point, so the
    // mail this card was created for is the right one to read.
    // svelte-ignore state_referenced_locally
    const cachedBody = loaded.get(mail.id);

    let body: EmailBody | null = $state(cachedBody ?? null);

    /** Whether the body arrives rather than being there: only then is there anything to fade. */
    let bodyArrives = $state(cachedBody === undefined);

    $effect(() => {
        const id = mail.id;

        const cached = loaded.get(id);
        if (cached !== undefined) {
            body = cached;
            bodyArrives = false;
            return;
        }

        body = null;
        bodyArrives = true;

        let current = true;
        loadBody(id).then((next) => {
            if (current) body = next;
        });
        return () => current = false;
    });

    const subject = $derived(mail.subject.trim());

    /** Only a name if the mail's header carried one; the address stands in for it. */
    const senderName = $derived(displayName(mail.sender));

    /** Unix seconds off the wire. */
    const sentAt = $derived(new Date(mail.sent * 1000));
</script>

<!--
    The whole card is the link to the mail's page -- a real anchor, so middle click, cmd-click and
    "open in new tab" do what they do everywhere else. Nothing inside it is interactive, the
    magnifier included, so there is no nested target to get in its way.

    `class` goes last, so the caller can place and size the card.
-->
<a
        href={emailPath(mail.id, mail.subject)}
        class={cn(
            "flex w-full flex-col overflow-hidden rounded-xl border bg-card text-card-foreground outline-none transition-colors hover:border-foreground/20 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30",
            className,
        )}
>
    <!-- A fixed height rather than the mail's own: every card in a list is the same shape, and
         what is past it is what opening the mail is for. -->
    <div class="relative h-40 shrink-0">
        {#if body}
            <!-- A body that was fetched fades in; one that was already here simply is here. The
                 transition sits on a wrapper because that is what a transition needs. -->
            <div
                    class="absolute inset-0"
                    in:fade={{duration: bodyArrives ? CONTENT_FADE_MS : 0}}
            >
                <EmailBodyPreview {body} class="absolute inset-0"/>
            </div>
        {:else}
            <div class="flex h-full items-center justify-center">
                <Spinner/>
            </div>
        {/if}

        <!-- How long ago, over the mail rather than beside it: it belongs to the whole card, and
             this is the one corner of a mail that is reliably empty. pointer-events-none, so the
             magnifier keeps following the cursor through it. -->
        <RelativeTime
                date={sentAt}
                class="pointer-events-none absolute right-2 top-2 z-10 rounded-full bg-card/70 px-2 py-0.5 text-xs font-light text-muted-foreground backdrop-blur-sm"
        />

        <!-- The mail does not end here, it is cut off here; the fade is what says so. -->
        <div class="pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-b from-transparent to-card"></div>
    </div>

    <!-- pt-6: room for the avatar, which hangs into this block from the edge above. -->
    <div class="relative flex min-w-0 flex-col gap-2 border-t p-3 pt-6">
        <!-- Centred on the preview's lower edge, with the card colour as a ring so the border
             line stops at the circle instead of running behind it. -->
        <OvermailCircularAvatar
                url={mail.sender.avatarUrl}
                padding={mail.sender.avatarPadding}
                name={senderName}
                class="absolute -top-4 left-3 size-8 border border-border bg-card ring-2 ring-card"
                fallbackClass="text-xs"
        />

        <!-- Two lines of subject: enough for the long ones, and the same shape for all of them.
             The stand-in for a mail without a subject stays muted -- it is not what the mail says. -->
        <span class={cn("line-clamp-2 text-sm font-medium wrap-anywhere", subject === "" && "font-normal text-muted-foreground")}>
            {subject || $_("mails.noSubject")}
        </span>

        <!-- min-w-0: a flex item does not shrink below its content on its own, so a long address
             would push the card wider than the column it is in. -->
        <div class="flex min-w-0 flex-col leading-tight">
            <span class="truncate text-xs">{senderName}</span>
            {#if mail.sender.name}
                <span class="truncate text-[0.6875rem] text-muted-foreground">{mail.sender.address}</span>
            {/if}
        </div>

        {#if mail.labels.length > 0}
            <!-- Three of them at most, the rest is a count; that is the badges' own rule. -->
            <MailLabelBadges labels={mail.labels} size="sm"/>
        {/if}
    </div>
</a>
