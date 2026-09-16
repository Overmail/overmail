<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte.ts";
    import { PaperclipIcon } from "phosphor-svelte";
    import Attachment from "$lib/app/mails/detail_panel/attachments/Attachment.svelte";
    import {cn} from "$lib/utils.ts";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    let {
        mail,
        class: className = "",
    }: {
        mail: EmailMeta;
        class?: string;
    } = $props();

    const {mails} = useRepositories();

    /** Running downloads, by attachment id. */
    let progress = $state<Record<string, number>>({});

    async function download(attachment: EmailMeta["attachments"][number]) {
        if (attachment.id in progress) return;
        progress[attachment.id] = 0;
        try {
            await mails.downloadAttachment(mail.id, attachment, value => progress[attachment.id] = value);
        } finally {
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
        {#each mail.attachments as attachment}
            <Attachment
                    attachment={attachment}
                    downloadProgress={progress[attachment.id] ?? null}
                    onclick={() => download(attachment)}
            />
        {/each}
    </div>
</div>