<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {Button} from "$lib/components/ui/button";
    import {
        ArchiveIcon,
        ArrowSquareUpRightIcon,
        CaretDownIcon,
        CaretUpIcon,
        CheckIcon,
        EnvelopeSimpleIcon,
        EnvelopeSimpleOpenIcon,
        LinkSimpleIcon,
        ProhibitIcon,
        ShareNetworkIcon,
        TrayArrowDownIcon,
        XIcon
    } from "phosphor-svelte";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {emailSlug} from "$lib/app/mails/emailPath";
    import {page} from "$app/state";

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
    } = $props();

    const emailPageUrl = $derived(page.url.origin + "/emails/" + emailSlug(mail.id, mail.subject));

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

<div class="flex flex-row items-center justify-between w-full px-4">
    <div class="flex flex-row items-center">
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
            </Tooltip.Content>
        </Tooltip.Root>

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
                E-Mail Teilen
            </Tooltip.Content>
        </Tooltip.Root>

        {#if mail.archiveState === "archive" || mail.archiveState === "spam"}
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
                </Tooltip.Content>
            </Tooltip.Root>
        {:else}
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
                </Tooltip.Content>
            </Tooltip.Root>

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
        {/if}

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        class="mr-1"
                        variant="ghost"
                        size="icon"
                        onclick={copyLinkToClipboard}
                >
                    {#if !showCopyCheckmark}
                        <LinkSimpleIcon />
                    {:else}
                        <CheckIcon />
                    {/if}
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                E-Mail URL kopieren
            </Tooltip.Content>
        </Tooltip.Root>

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        class="mr-1"
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
            </Tooltip.Content>
        </Tooltip.Root>
    </div>
</div>