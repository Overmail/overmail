<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import {Separator} from "$lib/components/ui/separator";
    import KeyCap from "$lib/components/key/KeyCap.svelte";
    import EmailStack from "$lib/app/my-stack/EmailStack.svelte";
    import emails from "$lib/assets/emails.json";
    import type {EmailStackEntry} from "$lib/app/my-stack/EmailStack.svelte";
    import {CLASSIFICATION_KEY_CLASSES, type EmailClassification} from "$lib/app/my-stack/classification";
    import TagInput from "$lib/app/my-stack/TagInput.svelte";
    import {KNOWN_TAGS} from "$lib/app/my-stack/tags";
    import {createHotkeys, getIsKeyHeld} from "@tanstack/svelte-hotkeys";
    import {cn} from "$lib/utils.js";
    import { ArchiveIcon, ArrowBendDownLeftIcon, ArrowDownIcon, ArrowUpIcon, ChatsCircleIcon, TagIcon, WarningIcon } from "phosphor-svelte";
    import { fade } from "svelte/transition";

    // Local state until the mail repository lands: the keys only move `currentId` around and write
    // a classification, nothing is persisted yet.
    let mails = $state<EmailStackEntry[]>(emails);
    let currentId = $state<string | undefined>(mails[0]?.id);

    const currentIndex = $derived(mails.findIndex((mail) => mail.id === currentId));

    // What the tag input is working on. Kept aside while tagging and written to the mail on Enter,
    // so leaving with Escape drops the edit instead of half-tagging the mail.
    let draftTags = $state<string[]>([]);

    /** The next mail still waiting for a decision, searching from `from` in `step` direction. */
    function undecidedFrom(from: number, step: 1 | -1): EmailStackEntry | undefined {
        for (let i = from; i >= 0 && i < mails.length; i += step) {
            if (!mails[i].classification) return mails[i];
        }
        return undefined;
    }

    function classify(to: EmailClassification["to"]) {
        if (currentIndex < 0) return;

        mails[currentIndex].classification = {to};
        currentId = undecidedFrom(currentIndex + 1, 1)?.id;
    }

    /** Skips the mail without deciding on it, so it stays in the stack. */
    function next() {
        if (currentIndex < 0) return;

        currentId = undecidedFrom(currentIndex + 1, 1)?.id ?? currentId;
    }

    /**
     * Back to the mail before this one. A decision on that mail is dropped on the way, so going
     * back is also the undo: the card slides in from the right again.
     */
    function previous() {
        const from = (currentIndex < 0 ? mails.length : currentIndex) - 1;
        if (from < 0) return;

        mails[from].classification = undefined;
        currentId = mails[from].id;
    }

    function startTagging() {
        if (currentIndex < 0) return;

        draftTags = [...(mails[currentIndex].tags ?? [])];
        isTagging = true;
    }

    /** Enter on an empty field: the draft goes to the mail and the stack takes over again. */
    function commitTags() {
        if (currentIndex >= 0) mails[currentIndex].tags = draftTags;
        isTagging = false;
    }

    // While tagging, every stack key is off and only Escape is left: a tag is typed, and a tag
    // name that contains an "a" must not archive the mail under it.
    const whileNotTagging = () => ({enabled: !isTagging});

    createHotkeys([
        {hotkey: "A", callback: () => classify("archive"), options: whileNotTagging},
        {hotkey: "S", callback: () => classify("spam"), options: whileNotTagging},
        {hotkey: "R", callback: () => classify("respond_later"), options: whileNotTagging},
        {hotkey: "Space", callback: next, options: whileNotTagging},
        {hotkey: "Backspace", callback: previous, options: whileNotTagging},
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
</script>

<header
        class="flex shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">Stack</h1>
        <div class="ms-auto flex items-center gap-2">
            <!-- End of header -->
        </div>
    </div>
</header>

<!-- flex-1 down the whole chain: <main> sits in the sidebar inset's flex column, and only a
     flex-1 item picks up the height left over by the header. A percentage height has nothing to
     resolve against here, since none of these boxes has a height of its own. -->
<main class="flex flex-1 flex-col">
    <div class="relative flex flex-1">
        <div class="flex flex-1 overflow-hidden relative">
            <div class="absolute inset-0 flex justify-center">
                <EmailStack emails={mails} {currentId} class="h-full" />
            </div>
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
                        <KeyCap key="A" isPressed={held.archive.held} label="Archivieren" onclick={() => classify("archive")} class={cn("size-10", CLASSIFICATION_KEY_CLASSES.archive)} />
                        <span class="flex flex-row items-center gap-1"><ArchiveIcon /> Archivieren</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="S" isPressed={held.spam.held} label="Spam" onclick={() => classify("spam")} class={cn("size-10", CLASSIFICATION_KEY_CLASSES.spam)} />
                        <span class="flex flex-row items-center gap-1"><WarningIcon /> Spam</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="R" isPressed={held.respondLater.held} label="Später antworten" onclick={() => classify("respond_later")} class={cn("size-10", CLASSIFICATION_KEY_CLASSES.respond_later)} />
                        <span class="flex flex-row items-center gap-1"><ChatsCircleIcon /> Später antworten</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="#" label="Taggen" onclick={startTagging} class="size-10" />
                        <span class="flex flex-row items-center gap-1"><TagIcon /> Taggen</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="␣" isPressed={held.next.held} label="Weiter" onclick={next} class="size-10" />
                        <span class="flex flex-row items-center gap-1"><ArrowDownIcon /> Weiter</span>
                    </div>

                    <div class="flex flex-row items-center justify-center gap-2">
                        <KeyCap key="⌫" isPressed={held.previous.held} label="Vorige Mail" onclick={previous} class="size-10" />
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