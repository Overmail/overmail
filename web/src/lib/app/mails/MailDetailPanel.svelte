<!--
    The mail that is open beside the list: what the table is pointing at, and the four things that
    can be done with it from here.

    Pinned to the window like the assistant's panel and next to it -- --panel-offset is what that
    one takes of the window's inline end, so the two sit side by side however wide the assistant
    is dragged. Below it (z-40 to its z-50) and above the header: the assistant slides in over
    this one, and the two edges pass each other during those 200ms.

    The shadow is spelled out rather than a `shadow-*` step: those cast downwards, and the only
    edge of this panel that is over the page is the inline start one -- that is the side the list
    has to read as lying underneath.

    Which mail is open is not held here but in the url; this is handed the id and says what
    happens to it (see MailTable).
-->
<script lang="ts">
    import {untrack} from "svelte";
    import {quintOut} from "svelte/easing";
    import {_} from "svelte-i18n";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {
        ArrowDownIcon,
        ArrowSquareOutIcon,
        ArrowUpIcon,
        BoxArrowDownIcon,
        BoxArrowUpIcon,
        DotsThreeVerticalIcon,
        EnvelopeSimpleIcon,
        EnvelopeSimpleOpenIcon,
        ProhibitIcon,
        XIcon,
    } from "phosphor-svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {emailPath} from "$lib/app/mails/emailPath";
    import type {MailStep} from "$lib/app/mails/MailListViewModel.svelte";

    /** Short and hard out of the gate: the panel is a step in a list, not a page turning. */
    const DURATION_MS = 150;

    /**
     * One of the icon buttons in the bar: one label, which is what the tooltip shows and what a
     * screen reader reads, so the two cannot say different things.
     */
    type Action = {
        label: string;
        /** Every phosphor icon has the same signature, so one of them types the lot. */
        Icon: typeof XIcon;
        onclick?: () => void;
        /** Instead of [onclick] where the action is a place rather than a change. */
        href?: string;
        disabled?: boolean;
    };

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

    /** Anything but the mailbox: archived, or filed as spam. */
    const isFiled = $derived(mail !== null && mail.archiveState !== "unarchive");

    // Both buttons say what pressing them does, not what the mail is -- that is what the label a
    // screen reader reads has to be, and the icon follows it.
    const readAction: Action = $derived(
        mail?.isRead
            ? {
                label: $_("mails.panel.markUnread"),
                Icon: EnvelopeSimpleIcon,
                onclick: () => void mails.setRead(id, false),
            }
            : {
                label: $_("mails.panel.markRead"),
                Icon: EnvelopeSimpleOpenIcon,
                onclick: () => void mails.setRead(id, true),
                disabled: mail === null,
            }
    );

    const archiveAction: Action = $derived(
        isFiled
            ? {
                label: $_("mails.panel.unarchive"),
                Icon: BoxArrowUpIcon,
                onclick: () => void mails.setArchiveStateTo(id, "unarchive"),
            }
            : {
                label: $_("mails.panel.archive"),
                Icon: BoxArrowDownIcon,
                onclick: () => void mails.setArchiveStateTo(id, "archive"),
                disabled: mail === null,
            }
    );

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
        class="fixed inset-y-0 inset-e-(--panel-offset) z-40 flex w-2xl flex-col border-s bg-background
               shadow-[-8px_0_24px_-8px_rgb(0_0_0/0.18)]
               transition-[left,right] duration-(--panel-duration) ease-linear"
        transition:slide
>
    <!-- Every one of these is an icon and nothing else, so each carries its label twice: once
         for the pointer as a tooltip, once for a screen reader. -->
    {#snippet action({label, Icon, onclick, href, disabled}: Action)}
        <Tooltip.Root>
            <Tooltip.Trigger>
                {#snippet child({props})}
                    <Button
                            {...props}
                            size="icon-sm"
                            variant="ghost"
                            {href}
                            {onclick}
                            {disabled}
                            target={href ? "_blank" : undefined}
                    >
                        <Icon/>
                        <span class="sr-only">{label}</span>
                    </Button>
                {/snippet}
            </Tooltip.Trigger>
            <Tooltip.Content side="bottom">{label}</Tooltip.Content>
        </Tooltip.Root>
    {/snippet}

    <div class="flex flex-row items-center justify-between gap-1 p-2">
        <div class="flex flex-row items-center gap-1">
            {@render action({
                label: $_("mails.panel.previous"),
                Icon: ArrowUpIcon,
                onclick: () => onStep(-1),
                disabled: !canStepUp,
            })}
            {@render action({
                label: $_("mails.panel.next"),
                Icon: ArrowDownIcon,
                onclick: () => onStep(1),
                disabled: !canStepDown,
            })}
        </div>

        <div class="flex flex-row items-center gap-1">
            {@render action(readAction)}
            {@render action(archiveAction)}

            {#if !isFiled}
                <!-- Spam is a decision about a mail that is still in the mailbox: from here it
                     goes out, and a mail that is already out is moved back with the button
                     beside this one instead. No tooltip on the trigger -- it opens a menu that
                     would sit under it, and the menu says what it does. -->
                <DropdownMenu.Root>
                    <DropdownMenu.Trigger>
                        {#snippet child({props})}
                            <Button {...props} size="icon-sm" variant="ghost" disabled={mail === null}>
                                <DotsThreeVerticalIcon/>
                                <span class="sr-only">{$_("mails.panel.more")}</span>
                            </Button>
                        {/snippet}
                    </DropdownMenu.Trigger>

                    <DropdownMenu.Content align="end" class="w-56">
                        <DropdownMenu.Item onclick={() => void mails.setArchiveStateTo(id, "spam")}>
                            <ProhibitIcon/>
                            {$_("mails.panel.markSpam")}
                        </DropdownMenu.Item>
                    </DropdownMenu.Content>
                </DropdownMenu.Root>
            {/if}

            <!-- A link, not a button that navigates: the mail has a page of its own, and this is
                 the way to it that can be middle-clicked and copied like any other. -->
            {@render action({
                label: $_("mails.panel.openInNewTab"),
                Icon: ArrowSquareOutIcon,
                href: emailPath(id, mail?.subject),
            })}
            {@render action({
                label: $_("mails.panel.close"),
                Icon: XIcon,
                onclick: onClose,
            })}
        </div>
    </div>

    <div class="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 pb-4">
        {#if mail !== null}
            <h2 class="font-display text-lg wrap-anywhere">
                {mail.subject.trim() === "" ? $_('mails.noSubject') : mail.subject}
            </h2>
        {:else if entry.isLoading}
            <Skeleton class="h-6 w-2/3"/>
        {:else}
            <p class="text-muted-foreground text-sm">{$_('mails.panel.missing')}</p>
        {/if}
    </div>
</div>
