<script module lang="ts">
    /**
     * One formatter for the whole stack. Building an Intl formatter costs far more than using one,
     * and the card takes the send time as text, see `EmailCard`.
     */
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
    import {Button} from "$lib/components/ui/button";
    import KeyCap from "$lib/components/key/KeyCap.svelte";
    import EmailStack from "$lib/app/my-stack/EmailStack.svelte";
    import type {EmailStackEntry} from "$lib/app/my-stack/EmailStack.svelte";
    import type {EmailCardParticipant} from "$lib/app/my-stack/EmailCard.svelte";
    import {StackStore, type StackEntry} from "$lib/app/my-stack/StackStore.svelte";
    import {avatarStore} from "$lib/app/avatars/AvatarStore.svelte";
    import {CLASSIFICATION_KEY_CLASSES} from "$lib/app/my-stack/classification";
    import TagInput from "$lib/app/my-stack/TagInput.svelte";
    import {KNOWN_TAGS} from "$lib/app/my-stack/tags";
    import type {MailParticipant} from "$lib/repository/MailRepository";
    import {createHotkeys, getIsKeyHeld} from "@tanstack/svelte-hotkeys";
    import {cn} from "$lib/utils.js";
    import { ArchiveIcon, ArrowBendDownLeftIcon, ArrowDownIcon, ArrowUpIcon, ChatsCircleIcon, TagIcon, TrayIcon, WarningIcon } from "phosphor-svelte";
    import { fade } from "svelte/transition";

    // Owned by the page rather than shared from a module, like the mailbox on the home page: a
    // module-level instance would be one stack for every server-rendered request.
    const stack = new StackStore();

    // What this reads is how many mails are still waiting, which is exactly what a decision
    // changes and therefore when the next pack is due -- and nothing the store writes while a
    // request is out, so it does not run again for every step of the request it starts. The first
    // pack comes from here too: a stack that has nothing in it has run down.
    $effect(() => stack.ensureFilled());

    // Leaving the screen hangs the socket up; nothing else holds it.
    $effect(() => () => stack.close());

    // One list of pictures for the whole mailbox, so it is asked for here rather than per card.
    $effect(() => avatarStore.ensureLoaded());

    // Which mail is on top belongs to the store, not here: the stack refills by how much is left
    // in front of the top card, so it is the store that has to know where that is.
    const entries = $derived(stack.entries);

    // What the tag input is working on. Kept aside while tagging and written to the mail on Enter,
    // so leaving with Escape drops the edit instead of half-tagging the mail.
    let draftTags = $state<string[]>([]);

    function startTagging() {
        if (!stack.top) return;

        draftTags = [...stack.top.tags];
        isTagging = true;
    }

    /** Enter on an empty field: the draft goes to the mail and the stack takes over again. */
    function commitTags() {
        stack.setTags(draftTags);
        isTagging = false;
    }

    // While tagging, every stack key is off and only Escape is left: a tag is typed, and a tag
    // name that contains an "a" must not archive the mail under it.
    const whileNotTagging = () => ({enabled: !isTagging});

    createHotkeys([
        {hotkey: "A", callback: () => stack.classify("archive"), options: whileNotTagging},
        {hotkey: "S", callback: () => stack.classify("spam"), options: whileNotTagging},
        {hotkey: "R", callback: () => stack.classify("respond_later"), options: whileNotTagging},
        {hotkey: "Space", callback: () => stack.skip(), options: whileNotTagging},
        {hotkey: "Backspace", callback: () => stack.back(), options: whileNotTagging},
        // '#' sits on its own key on a German layout and on Shift+3 on a US one, and the matcher
        // compares Shift strictly, so both spellings are bound.
        {hotkey: {key: "#"}, callback: startTagging, options: whileNotTagging},
        {hotkey: {key: "#", shift: true}, callback: startTagging, options: whileNotTagging},
        {hotkey: "Escape", callback: () => (isTagging = false)},
    ]);

    // Drives the keycaps: the same key state the hotkeys run off, so a cap can never look pressed
    // while its shortcut is not firing. `#` is left out: the bar it sits in is gone while tagging.
    const held = {
        archive: getIsKeyHeld("A"),
        spam: getIsKeyHeld("S"),
        respondLater: getIsKeyHeld("R"),
        next: getIsKeyHeld("Space"),
        previous: getIsKeyHeld("Backspace"),
    };

    /** Whether the stack is waiting for a tag for the current mail. */
    let isTagging = $state(false);

    /** The mails as the cards want them: display names, formatted times and pictures. */
    const cards = $derived(entries.map(toCard));

    function toCard(entry: StackEntry): EmailStackEntry {
        const {mail} = entry;

        return {
            id: mail.id,
            sender: {
                ...toCardParticipant(mail.sender),
                // Absent for an address no picture was found for; the card falls back to initials.
                avatarUrl: avatarStore.urlFor(mail.sender.address) ?? undefined,
            },
            sent: SENT_FORMAT.format(new Date(mail.sent_at)),
            to: mail.recipients.map(toCardParticipant),
            cc: mail.cc.map(toCardParticipant),
            bcc: mail.bcc.map(toCardParticipant),
            subject: mail.subject,
            body: entry.body,
            tags: entry.tags,
            classification: entry.classification,
        };
    }

    function toCardParticipant(participant: MailParticipant): EmailCardParticipant {
        return {name: participant.name ?? undefined, address: participant.address};
    }

    // Only once a pack has come back and nothing is on its way or has failed: an empty list says
    // nothing before that, a full mailbox would read as a finished stack, and a failed request is
    // the header's message rather than a finished one.
    const isEmpty = $derived(
        stack.initialized && stack.waiting.length === 0 && stack.status === 'idle',
    );
</script>

<header
        class="flex shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">Stack</h1>
        <div class="ms-auto flex items-center gap-2">
            <!-- The stack itself keeps working on what it has; asking again is the way out of a
                 failed request, and it belongs where it is reachable with a card on screen. -->
            {#if stack.status === 'error'}
                <span class="text-muted-foreground text-sm">Mails konnten nicht geladen werden.</span>
                <Button variant="outline" size="sm" onclick={() => stack.retry()}>
                    Erneut versuchen
                </Button>
            {/if}
        </div>
    </div>
</header>

<!-- flex-1 down the whole chain: <main> sits in the sidebar inset's flex column, and only a
     flex-1 item picks up the height left over by the header. A percentage height has nothing to
     resolve against here, since none of these boxes has a height of its own. -->
<main class="flex flex-1 flex-col">
    <div class="relative flex flex-1">
        <div class="flex flex-1 overflow-hidden relative">
            <!-- No centring here any more: the stack fills this box so that every card's scroll
                 container reaches the right-hand edge, and the card inside it is what gets
                 centred. -->
            <div class="absolute inset-0">
                <EmailStack emails={cards} currentId={stack.topId} class="h-full" />
            </div>

            <!-- Beside the cards rather than instead of them: the stack stays mounted while the
                 last decided card is still flying out, and this is what is left behind once it is
                 gone -- which is what the delay on the fade waits for. -->
            {#if isEmpty}
                <div
                        class="absolute inset-0 flex items-center justify-center px-8 pb-32"
                        transition:fade={{duration: 200, delay: 400}}
                >
                    <Empty.Root>
                        <Empty.Header>
                            <Empty.Media variant="icon">
                                <TrayIcon />
                            </Empty.Media>
                            <Empty.Title>Stack abgearbeitet</Empty.Title>
                            <Empty.Description>
                                Jede Mail ist entschieden. Was neu importiert wird, landet wieder
                                hier.
                            </Empty.Description>
                        </Empty.Header>
                    </Empty.Root>
                </div>
            {/if}
        </div>


        <div class="absolute bottom-0 left-0 z-50 w-full h-32 bg-linear-to-b from-transparent to-background">
            <!-- One masked backdrop-filter, where this used to be four stacked ones for a blur that
                 ramps up towards the bottom. The ramp is what made it expensive: what a
                 backdrop-filter costs is not its own blur but the backdrop it needs, and that
                 backdrop is everything painted below it - the whole stack - flattened into one
                 texture. That happens once per backdrop-filter element per frame, so four layers
                 meant flattening the stack four times a frame for as long as anything on the page
                 moved. One layer with a soft mask reads almost the same: a constant blur fading in
                 rather than one growing stronger, under a gradient that washes it out towards the
                 bottom anyway. If this still stutters, the blur has to go - drop this one line and
                 the gradient on the parent carries the effect on its own. -->
            <div class="pointer-events-none absolute inset-0 backdrop-blur-[10px] mask-[linear-gradient(to_bottom,transparent_0%,black_70%)]"></div>

            <!-- relative, so the keys paint above the absolutely positioned blur layers. -->
            {#if !isTagging}
                <div
                        class="absolute top-0 left-0 flex w-full h-full flex-row items-center justify-center gap-6"
                        transition:fade={{duration: 100}}
                >
                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="A" isPressed={held.archive.held} label="Archivieren" onclick={() => stack.classify("archive")} class={cn("size-10", CLASSIFICATION_KEY_CLASSES.archive)} />
                        <span class="flex flex-row items-center gap-1"><ArchiveIcon /> Archivieren</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="S" isPressed={held.spam.held} label="Spam" onclick={() => stack.classify("spam")} class={cn("size-10", CLASSIFICATION_KEY_CLASSES.spam)} />
                        <span class="flex flex-row items-center gap-1"><WarningIcon /> Spam</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="R" isPressed={held.respondLater.held} label="Später antworten" onclick={() => stack.classify("respond_later")} class={cn("size-10", CLASSIFICATION_KEY_CLASSES.respond_later)} />
                        <span class="flex flex-row items-center gap-1"><ChatsCircleIcon /> Später antworten</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="#" label="Taggen" onclick={startTagging} class="size-10" />
                        <span class="flex flex-row items-center gap-1"><TagIcon /> Taggen</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="␣" isPressed={held.next.held} label="Weiter" onclick={() => stack.skip()} class="size-10" />
                        <span class="flex flex-row items-center gap-1"><ArrowDownIcon /> Weiter</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="⌫" isPressed={held.previous.held} label="Vorige Mail" onclick={() => stack.back()} class="size-10" />
                        <span class="flex flex-row items-center gap-1"><ArrowUpIcon /> Vorige Mail</span>
                    </div>
                </div>
            {:else}
                <div
                        class="absolute top-0 left-0 flex w-full h-full flex-row items-center justify-center gap-6"
                        transition:fade={{duration: 100}}
                >
                    <TagInput bind:tags={draftTags} suggestions={KNOWN_TAGS} onclose={commitTags} class="w-xl" />

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="ESC" label="Vorige Mail" onclick={() => isTagging = false} class="size-10" />
                        <span class="flex flex-row items-center gap-1"><ArrowBendDownLeftIcon /> Zurück</span>
                    </div>
                </div>
            {/if}
        </div>
    </div>
</main>
