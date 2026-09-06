<script module lang="ts">
    import {quintOut} from "svelte/easing";

    /**
     * What the panel takes up, and how it gets there: its width in pixels, and the slide it
     * comes in and goes out on. Short and hard out of the gate -- the panel is a step in a list,
     * not a page turning.
     *
     * One description for both, because the header steps its own controls out from under the
     * panel and has to move *with* it rather than merely at the same time: run against the
     * closest curve CSS has a name for, this one is a hundred pixels ahead of it halfway
     * through.
     */
    export const PANEL_COVER = {width: 672, motion: {durationMs: 150, easing: quintOut}};
</script>

<script lang="ts">
    import {untrack} from "svelte";
    import {cn} from "$lib/utils";
    import {createHotkey} from "@tanstack/svelte-hotkeys";
    import {fly} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import {useRepositories} from "$lib/repository/repositories";
    import Head from "./Head.svelte";
    import type {MailStep} from "$lib/app/mails/MailListViewModel.svelte.js";
    import Detail from "$lib/app/mails/detail_panel/Detail.svelte";
    import {MAIL_BOX_TRANSITION, MAIL_SUBJECT_TRANSITION, isMorphing} from "$lib/app/mails/mailViewTransition";
    import ShareDialog from "$lib/app/mails/detail_panel/share/ShareDialog.svelte";

    /**
     * How far the content moves when the mail changes, and for how long.
     *
     * A step moves it the way the list was walked -- down the table, and the next mail comes up
     * from below -- so the movement says which way you went. Small on purpose: it is a hint, not
     * a page turn. The one going out leaves faster than the one coming in arrives, or the two
     * cross in the middle and the panel reads as busy.
     */
    const SHIFT_PX = 64;
    const SWAP_IN_MS = 250;
    const SWAP_OUT_MS = 250;

    let {
        /** The open mail. Changing it is a step to another one, not another panel. */
        id,
        /** Whether there is a row above and below this one; the ends grey their button out. */
        canStepUp,
        canStepDown,
        onStep,
        onClose,
    }: {
        id: string;
        canStepUp: boolean;
        canStepDown: boolean;
        onStep: (step: MailStep) => void;
        onClose: () => void;
    } = $props();

    const {mails} = useRepositories();

    // Subscribed by this panel for as long as it holds the mail, rather than left to the table:
    // the open mail can be scrolled far past, and the rows the table keeps are the ones near the
    // viewport. Re-runs on a step, which releases the one before it.
    $effect(() => mails.subscribe(id));

    // Opening a mail is reading it: this is the id changing, and nothing else. The write reads
    // what the mail is to decide whether to write at all, so it is untracked -- otherwise its
    // own answer arriving over the socket would run this again.
    $effect(() => {
        const opened = id;
        untrack(() => void mails.setRead(opened, true));
    });

    const entry = $derived(mails.peek(id));
    const mail = $derived(entry.value);

    /**
     * How far the next swap of the content moves, with its sign: what the buttons of this panel
     * asked for. Zero for everything else -- a row clicked in the table, the back button -- which
     * is a jump rather than a step, and a jump only fades.
     */
    let shift = $state(0);

    function stepTo(direction: MailStep) {
        shift = direction * SHIFT_PX;
        onStep(direction);
    }

    // Whatever changes the mail next is a jump until a button says otherwise. This runs after the
    // block below was swapped, so the transitions of that swap have read it already.
    $effect(() => {
        void id;
        shift = 0;
    });

    /** The box under the bar that scrolls; see the panel below. */
    let scroller: HTMLElement | undefined = $state();

    /**
     * Whether the mail has been scrolled at all.
     *
     * The bar sits above the box rather than in it, so nothing passes behind it -- but the top of
     * the mail does leave the screen, and then a line under the bar says that there is more above
     * what is being read. Fades in, because it appears at the first pixel of scrolling and a line
     * that snaps in at that moment reads as a jolt.
     */
    let scrolled = $state(false);

    // Another mail starts at its top, and therefore without the line. The box is not replaced
    // when the mail changes -- only what is in it is -- so without this a step lands in the
    // middle of a body nobody has read.
    $effect(() => {
        void id;
        if (scroller) scroller.scrollTop = 0;
        scrolled = false;
    });

    /**
     * Where the archive shortcut sends the mail: out of the mailbox, or back into it. The same
     * rule the button in the bar follows, see Head.
     */
    const archiveTarget = $derived(
        mail !== null && mail.archiveState !== "unarchive" ? "unarchive" : "archive"
    );

    /*
     * The keyboard while a mail is open. On the document rather than on an element: there is
     * nothing here to focus, and the panel closing unregisters them with it.
     *
     * Single keys, which the library keeps out of text inputs on its own -- typing an "a" into the
     * label search must not archive the mail. Escape is not one of those by default and is told to
     * be, so it belongs to whatever is open on top of the panel first.
     */
    createHotkey(".", () => stepTo(1), () => ({enabled: canStepDown}));
    createHotkey(",", () => stepTo(-1), () => ({enabled: canStepUp}));
    createHotkey(
        "A",
        () => {
            if (mail !== null) void mails.setArchiveState(mail.id, archiveTarget);
        },
        () => ({enabled: mail !== null}),
    );
    createHotkey(
        "Escape",
        () => {
            // Whatever is open on top of the panel gets the key first: a menu or the label
            // popover closes with Escape, and closing the panel out from under it is not what
            // the reader meant. Both are only in the document while they are open.
            const overlay = document.querySelector(
                "[data-slot=dropdown-menu-content], [data-slot=popover-content]"
            );
            if (overlay !== null) return;

            onClose();
        },
        () => ({ignoreInputs: true}),
    );

    /**
     * In and out over the edge the panel is pinned to.
     *
     * Its own rather than `fly`, which wants the distance in pixels: a percentage of the node is
     * the panel's width whatever it is set to, and the sign follows the writing direction, so
     * this is the inline end in both.
     */
    function slide(node: HTMLElement) {
        // Not while the browser is morphing this panel into the mail's page: the slide would keep
        // it in the DOM, and with it a second element of the page's name. Without a duration
        // Svelte removes the node on the spot, and the morph is the only movement.
        if (isMorphing()) return {duration: 0};

        const sign = getComputedStyle(node).direction === "rtl" ? -1 : 1;

        return {
            duration: PANEL_COVER.motion.durationMs,
            easing: PANEL_COVER.motion.easing,
            css: (_t: number, u: number) => `transform: translateX(${sign * u * 100}%)`,
        };
    }

    let showShareDialog = $state(false);
</script>

<!--
    The box the browser morphs into the mail's own page, and back: the same name is on the column
    of that page, and a name that moves from one element to another across a navigation is what a
    view transition animates. See mailViewTransition.

    Its slide is `|local`, so it plays when the panel itself opens and closes and not when the
    route around it goes away; and it is skipped outright while a morph runs (see slide), because
    an outro would leave two elements carrying that name at once, which is not a thing the browser
    can morph -- it drops the transition and throws.
-->
<div
        style:width="{PANEL_COVER.width}px"
        style:view-transition-name={MAIL_BOX_TRANSITION}
        class="fixed inset-y-0 inset-e-(--panel-offset) z-40 flex flex-col border-s bg-background
               shadow-[-8px_0_24px_-8px_rgb(0_0_0/0.18)]
               transition-[left,right] duration-(--panel-duration) ease-linear"
        transition:slide|local
>
    {#if mail}
        <Head
                class={cn("border-b py-4 transition-colors", scrolled ? "border-border" : "border-transparent")}
                mail={mail}
                hasNextMail={canStepDown}
                hasPreviousMail={canStepUp}

                onClose={onClose}
                onNextMail={() => stepTo(1)}
                onPreviousMail={() => stepTo(-1)}
                onChangeArchiveState={(newState) => mails.setArchiveState(mail.id, newState)}
                onDownloadMail={() => mails.downloadMail(mail.id)}
                onShareMail={() => showShareDialog = true}
                onChangeReadState={(isRead) => mails.setRead(mail.id, isRead)}
                onReclassify={() => mails.requestClassification(mail.id)}
        />

        <!-- Everything the mail is scrolls in here, under a bar that stays put. -->
        <div
                bind:this={scroller}
                onscroll={() => (scrolled = (scroller?.scrollTop ?? 0) > 0)}
                class="min-h-0 flex-1 overflow-y-auto pt-6 pb-8"
        >
            <!--
                What the mail is gets swapped; the bar above it stays where it is, or its buttons
                would move out from under the cursor between two clicks.

                Both halves of the swap sit in the one grid cell, so they cross over each other
                instead of pushing the panel apart while they do.
            -->
            <div class="grid grid-cols-1 grid-rows-1 *:col-start-1 *:row-start-1">
                {#key mail.id}
                    <div
                            in:fly={{y: shift, duration: SWAP_IN_MS, easing: cubicOut}}
                            out:fly={{y: -shift, duration: SWAP_OUT_MS, easing: cubicOut}}
                            class="flex flex-col gap-6"
                    >
                        <!-- The subject above the mail, and not part of it: the page has it in the
                             line with the tools, and the browser carries the words from here to
                             there. Detail then begins with the same line at both ends, so the mail
                             does not travel through its own heading on the way. The name is on the
                             text, inside the padding, so the snapshot starts where the heading's
                             does. See mailViewTransition. -->
                        <div class="px-6">
                            <div
                                    style:view-transition-name={MAIL_SUBJECT_TRANSITION}
                                    class="font-display text-2xl text-pretty"
                            >
                                {mail.subject}
                            </div>
                        </div>

                        <Detail {mail}/>
                    </div>
                {/key}
            </div>
        </div>
    {/if}
</div>

<!-- Mounted only while it is open, so each mail gets a dialog on its own links. -->
{#if showShareDialog && mail}
    <ShareDialog bind:open={showShareDialog} emailId={mail.id} />
{/if}
