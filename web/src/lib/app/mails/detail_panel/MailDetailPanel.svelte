<script lang="ts">
    import {untrack} from "svelte";
    import {quintOut} from "svelte/easing";
    import {useRepositories} from "$lib/repository/repositories";
    import Head from "./Head.svelte";
    import type {MailStep} from "$lib/app/mails/MailListViewModel.svelte.js";
    import Labels from "$lib/app/mails/detail_panel/Labels.svelte";

    /** Short and hard out of the gate: the panel is a step in a list, not a page turning. */
    const DURATION_MS = 150;

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
     * In and out over the edge the panel is pinned to.
     *
     * Its own rather than `fly`, which wants the distance in pixels: a percentage of the node is
     * the panel's width whatever it is set to, and the sign follows the writing direction, so
     * this is the inline end in both.
     */
    function slide(node: HTMLElement) {
        const sign = getComputedStyle(node).direction === "rtl" ? -1 : 1;

        return {
            duration: DURATION_MS,
            easing: quintOut,
            css: (_t: number, u: number) => `transform: translateX(${sign * u * 100}%)`,
        };
    }
</script>

<div
        class="fixed inset-y-0 inset-e-(--panel-offset) z-40 flex w-2xl flex-col gap-2 py-4 border-s bg-background
               shadow-[-8px_0_24px_-8px_rgb(0_0_0/0.18)]
               transition-[left,right] duration-(--panel-duration) ease-linear"
        transition:slide
>
    {#if mail}
        <Head
                mail={mail}
                hasNextMail={canStepDown}
                hasPreviousMail={canStepUp}

                onClose={onClose}
                onNextMail={() => onStep(1)}
                onPreviousMail={() => onStep(-1)}
                onChangeArchiveState={(newState) => mails.setArchiveState(mail.id, newState)}
                onShareMail={() => alert("Sharing is not yet supported. Note that sharing a mail is not the same as forwarding it.")}
                onChangeReadState={(isRead) => mails.setRead(mail.id, isRead)}
                onReclassify={() => mails.requestClassification(mail.id)}
        />

        <div class="px-6 font-display text-2xl mt-4 text-pretty">
            {mail.subject}
        </div>

        <!-- Every one of these is a write about the open mail, so they go through the repository
             and come back over the socket as the mail's metadata. -->
        <Labels
                labels={mail.labels}
                onAddLabel={(label) => mails.attachLabel(mail.id, label.id)}
                onCreateLabel={(name) => mails.createLabelOn(mail.id, name)}
                onRemoveLabel={(label) => mails.detachLabel(mail.id, label.id)}
        />
    {/if}
</div>
