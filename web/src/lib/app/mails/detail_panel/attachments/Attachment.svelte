<script lang="ts">
    import type {Attachment} from "$lib/repository/EmailRepository.svelte.ts";
    import AttachmentIcon from "$lib/app/mails/detail_panel/attachments/AttachmentIcon.svelte";
    import {filesize} from "filesize";
    import { DownloadIcon } from "phosphor-svelte";
    import {_, locale} from "svelte-i18n";

    let {
        attachment,
        onclick = () => {},
        downloadProgress = null,
    }: {
        attachment: Attachment,
        onclick?: () => void,
        downloadProgress?: number | null,
    } = $props();

    let isHovering = $state(false);
    let isClicking = $state(false);

    let isDownloading = $derived(downloadProgress != null);
    let showFiletypeIcon = $derived(!isDownloading && !isHovering);
    let showDownloadIcon = $derived(!showFiletypeIcon);

    let size = $derived(filesize(attachment.size, {locale: $locale ?? true}));
    let percent = $derived(
        new Intl.NumberFormat($locale ?? undefined, {style: "percent"}).format(downloadProgress ?? 0)
    );

    const RADIUS = 14;
    const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
</script>

<button
        class="flex flex-row items-center gap-2 p-2 rounded-lg border hover:bg-accent cursor-pointer transition-all"
        onmouseenter={() => isHovering = true}
        onmouseleave={() => isHovering = false}
        onpointerdown={() => isClicking = true}
        onpointerup={() => isClicking = false}
        class:scale-95={isClicking}
        onclick={onclick}
        aria-label={$_('mails.attachments.download', {values: {name: attachment.name}})}
>
    <div
            class="relative size-8"
    >
        <div
                class="absolute inset-0 flex items-center justify-center transition-all"
                class:opacity-0={!showFiletypeIcon}
                class:opacity-100={showFiletypeIcon}
                class:top-4={!showFiletypeIcon}
                class:top-0={showFiletypeIcon}
        >
            <AttachmentIcon name={attachment.name} contentType={attachment.contentType} />
        </div>

        <div
                class="size-8 absolute flex items-center justify-center transition-all"
                class:opacity-0={!showDownloadIcon}
                class:opacity-100={showDownloadIcon}
                class:bottom-0={showDownloadIcon}
                class:bottom-4={!showDownloadIcon}
        >
            <DownloadIcon class="size-4" />
            {#if isDownloading}
                <svg class="absolute inset-0 size-8 -rotate-90" viewBox="0 0 32 32">
                    <circle cx="16" cy="16" r={RADIUS} fill="none" stroke-width="2" class="stroke-border" />
                    <circle
                            cx="16" cy="16" r={RADIUS} fill="none" stroke-width="2" stroke-linecap="round"
                            class="stroke-primary transition-[stroke-dashoffset] duration-200"
                            stroke-dasharray={CIRCUMFERENCE}
                            stroke-dashoffset={CIRCUMFERENCE * (1 - (downloadProgress ?? 0))}
                    />
                </svg>
            {/if}
        </div>
    </div>

    <div class="flex flex-col text-accent-foreground items-start">
        <h3 class="text-sm">{attachment.name}</h3>
        <p class="text-xs text-muted-foreground tabular-nums">
            {isDownloading ? $_('mails.attachments.progress', {values: {size, percent}}) : size}
        </p>
    </div>
</button>
