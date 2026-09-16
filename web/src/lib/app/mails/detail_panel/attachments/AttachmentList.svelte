<!--
    A row of attachments with download, progress and cancel. Where the bytes come from is the
    caller's: the mail view downloads through the session, a share page through its link.
-->
<script lang="ts">
    import type {Attachment} from "$lib/repository/EmailRepository.svelte.ts";
    import { PaperclipIcon } from "phosphor-svelte";
    import AttachmentItem from "$lib/app/mails/detail_panel/attachments/Attachment.svelte";
    import {cn} from "$lib/utils.ts";
    import {_} from "svelte-i18n";

    let {
        attachments,
        download,
        class: className = "",
        onRestoreFocus,
    }: {
        attachments: Attachment[];
        /** Saves [attachment]; rejects with an `AbortError` once [signal] aborts. */
        download: (attachment: Attachment, onProgress: (progress: number) => void, signal: AbortSignal) => Promise<void>;
        class?: string;
        /** Hands the keyboard back after a click, where it belongs to something around the row (the stack). */
        onRestoreFocus?: () => void;
    } = $props();

    /** Running downloads, by attachment id. */
    let progress = $state<Record<string, number>>({});
    const downloads = new Map<string, AbortController>();

    /** Starts the download, or cancels it if it is already running. */
    async function toggleDownload(attachment: Attachment) {
        onRestoreFocus?.();

        const running = downloads.get(attachment.id);
        if (running) {
            running.abort();
            return;
        }

        const controller = new AbortController();
        downloads.set(attachment.id, controller);
        progress[attachment.id] = 0;
        try {
            await download(attachment, value => progress[attachment.id] = value, controller.signal);
        } catch (e) {
            if (!controller.signal.aborted) throw e;
        } finally {
            downloads.delete(attachment.id);
            delete progress[attachment.id];
        }
    }
</script>

<div class={cn("flex flex-col gap-1", className)}>
    <h2 class="flex items-center gap-1 text-sm font-medium tracking-tight text-gray-500">
        <PaperclipIcon class="w-4 h-4 text-gray-500" />
        <span>{$_('mails.attachments.title')}</span>
    </h2>

    <div class="flex flex-row items-center flex-wrap gap-2">
        {#each attachments as attachment (attachment.id)}
            <AttachmentItem
                    attachment={attachment}
                    downloadProgress={progress[attachment.id] ?? null}
                    onclick={() => toggleDownload(attachment)}
            />
        {/each}
    </div>
</div>
