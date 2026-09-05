<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {Button} from "$lib/components/ui/button";
    import {
        ArchiveIcon,
        ArrowSquareUpRightIcon,
        CaretDownIcon,
        CaretUpIcon,
        CheckIcon,
        DotsThreeVerticalIcon,
        EnvelopeSimpleIcon,
        EnvelopeSimpleOpenIcon,
        LinkSimpleIcon,
        ProhibitIcon,
        ShareNetworkIcon,
        SparkleIcon,
        TrayArrowDownIcon,
        XIcon
    } from "phosphor-svelte";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import * as Kbd from "$lib/components/ui/kbd";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {_} from "svelte-i18n";
    import {scale, slide} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import {emailSlug} from "$lib/app/mails/emailPath";
    import {page} from "$app/state";
    import {cn} from "$lib/utils";

    let {
        mail,
        hasNextMail,
        hasPreviousMail,

        onClose,
        onNextMail,
        onPreviousMail,
        onChangeArchiveState,
        onShareMail,
        onChangeReadState,
        onReclassify,
        class: propsClass,
    }: {
        mail: EmailMeta,
        hasNextMail: boolean,
        hasPreviousMail: boolean,

        onClose: () => void;
        onNextMail: () => void;
        onPreviousMail: () => void;
        onChangeArchiveState: (newState: EmailMeta["archiveState"]) => Promise<void>;
        onShareMail: () => void;
        onChangeReadState: (isRead: boolean) => Promise<void>;
        /** Hands the mail back to the classification agent; see the menu at the end of the bar. */
        onReclassify: () => void;
        class?: string;
    } = $props();

    /**
     * Zoom and fade, for two things that take the same place: the one going out and the one
     * coming in overlap, so the button reads as one thing changing rather than two swapping.
     * Short, because it says "that worked" and nothing more.
     */
    const SWAP_MS = 140;
    const swap = {duration: SWAP_MS, start: 0.4, opacity: 0, easing: cubicOut};

    const emailPageUrl = $derived(page.url.origin + "/emails/" + emailSlug(mail.id, mail.subject));

    /** Anything but the mailbox: archived, or filed as spam. Neither can be filed again. */
    const isFiled = $derived(mail.archiveState !== "unarchive");

    let showCopyCheckmark = $state(false);
    let hideCopyCheckmarkTimeout: ReturnType<typeof setTimeout> | null = $state(null);

    function copyLinkToClipboard() {
        navigator.clipboard.writeText(emailPageUrl);
        if (hideCopyCheckmarkTimeout) clearTimeout(hideCopyCheckmarkTimeout);
        showCopyCheckmark = true;
        hideCopyCheckmarkTimeout = setTimeout(() => {
            showCopyCheckmark = false;
            hideCopyCheckmarkTimeout = null;
        }, 2000);
    }
</script>

<div class={cn("flex flex-row items-center justify-between w-full px-4", propsClass)}>
    <div class="flex flex-row items-center">
        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon"
                        disabled={!hasPreviousMail}
                        onclick={onPreviousMail}
                >
                    <CaretUpIcon />
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                Vorige E-Mail
                <Kbd.Group>
                    <Kbd.Root>,</Kbd.Root>
                </Kbd.Group>
            </Tooltip.Content>
        </Tooltip.Root>

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon"
                        disabled={!hasNextMail}
                        onclick={onNextMail}
                >
                    <CaretDownIcon />
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                Nächste E-Mail
                <Kbd.Group>
                    <Kbd.Root>.</Kbd.Root>
                </Kbd.Group>
            </Tooltip.Content>
        </Tooltip.Root>
    </div>

    <div class="flex flex-row items-center">
        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon"
                        onclick={() => onChangeReadState(!mail.isRead)}
                >
                    {#if mail.isRead}
                        <EnvelopeSimpleIcon />
                    {:else}
                        <EnvelopeSimpleOpenIcon />
                    {/if}
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                {#if mail.isRead}
                    E-Mail als ungelesen markieren
                {:else}
                    E-Mail als gelesen markieren
                {/if}
            </Tooltip.Content>
        </Tooltip.Root>

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon"
                        onclick={onShareMail}
                >
                    <ShareNetworkIcon />
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                E-Mail teilen
            </Tooltip.Content>
        </Tooltip.Root>

        <!-- The way out and the way back sit in the same place, so they cross over each other
             instead of one replacing the other. A grid with both children in the one cell: the
             box is as big as they are, with nothing to keep in step by hand. -->
        <div class="grid grid-cols-1 grid-rows-1 *:col-start-1 *:row-start-1">
            {#if isFiled}
                <div transition:scale={swap}>
                    <Tooltip.Root>
                        <Tooltip.Trigger>
                            <Button
                                    variant="ghost"
                                    size="icon"
                                    onclick={() => onChangeArchiveState("unarchive")}
                            >
                                <TrayArrowDownIcon />
                            </Button>
                        </Tooltip.Trigger>

                        <Tooltip.Content>
                            Zurück ins Postfach verschieben
                            <Kbd.Group>
                                <Kbd.Root>A</Kbd.Root>
                            </Kbd.Group>
                        </Tooltip.Content>
                    </Tooltip.Root>
                </div>
            {:else}
                <div transition:scale={swap}>
                    <Tooltip.Root>
                        <Tooltip.Trigger>
                            <Button
                                    variant="ghost"
                                    size="icon"
                                    onclick={() => onChangeArchiveState("archive")}
                            >
                                <ArchiveIcon />
                            </Button>
                        </Tooltip.Trigger>

                        <Tooltip.Content>
                            Archivieren
                            <Kbd.Group>
                                <Kbd.Root>A</Kbd.Root>
                            </Kbd.Group>
                        </Tooltip.Content>
                    </Tooltip.Root>
                </div>
            {/if}
        </div>

        {#if !isFiled}
            <!-- Spam is only a decision about a mail that is in the mailbox. It squeezes out of
                 the row rather than leaving a hole in it: the width slides, the icon zooms. -->
            <div transition:slide={{axis: "x", duration: SWAP_MS, easing: cubicOut}}>
                <div transition:scale={swap}>
                    <Tooltip.Root>
                        <Tooltip.Trigger>
                            <Button
                                    variant="ghost"
                                    size="icon"
                                    onclick={() => onChangeArchiveState("spam")}
                            >
                                <ProhibitIcon />
                            </Button>
                        </Tooltip.Trigger>

                        <Tooltip.Content>
                            Als Spam markieren
                        </Tooltip.Content>
                    </Tooltip.Root>
                </div>
            </div>
        {/if}

        <Tooltip.Root>
            <Tooltip.Trigger>
                <!-- The checkmark grows out of the link rather than taking its place in the
                     next frame: both in the one grid cell, and the button centres them the way
                     it centres a single icon -- `grid` is the only thing it takes over from it. -->
                <Button
                        class="mr-1 grid *:col-start-1 *:row-start-1"
                        variant="ghost"
                        size="icon"
                        onclick={copyLinkToClipboard}
                >
                    {#if showCopyCheckmark}
                        <span transition:scale={swap}><CheckIcon /></span>
                    {:else}
                        <span transition:scale={swap}><LinkSimpleIcon /></span>
                    {/if}
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                E-Mail URL kopieren (Nur du kannst ihn verwenden)
            </Tooltip.Content>
        </Tooltip.Root>

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon"
                        href={emailPageUrl}
                        target="_blank"
                >
                    <ArrowSquareUpRightIcon />
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                In neuem Tab öffnen
            </Tooltip.Content>
        </Tooltip.Root>

        <!-- What is not worth a button of its own. No tooltip on the trigger: it opens a menu
             that would sit under it, and the menu says what it does. -->
        <DropdownMenu.Root>
            <DropdownMenu.Trigger>
                {#snippet child({props})}
                    <Button {...props} variant="ghost" size="icon">
                        <DotsThreeVerticalIcon />
                        <span class="sr-only">{$_('mails.panel.more')}</span>
                    </Button>
                {/snippet}
            </DropdownMenu.Trigger>

            <DropdownMenu.Content align="end" class="w-64">
                <DropdownMenu.Item onclick={onReclassify}>
                    <SparkleIcon />
                    {$_('mails.panel.reclassify')}
                </DropdownMenu.Item>
            </DropdownMenu.Content>
        </DropdownMenu.Root>

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        class="mr-1"
                        variant="ghost"
                        size="icon"
                        onclick={onClose}
                >
                    <XIcon />
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                Schließen
                <Kbd.Group>
                    <Kbd.Root>Esc</Kbd.Root>
                </Kbd.Group>
            </Tooltip.Content>
        </Tooltip.Root>
    </div>
</div>