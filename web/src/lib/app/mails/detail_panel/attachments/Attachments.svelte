<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte.ts";
    import AttachmentList from "$lib/app/mails/detail_panel/attachments/AttachmentList.svelte";
    import {useRepositories} from "$lib/repository/repositories";

    let {
        mail,
        class: className = "",
        onRestoreFocus,
    }: {
        mail: EmailMeta;
        class?: string;
        /** Hands the keyboard back after a click, where it belongs to something around the row (the stack). */
        onRestoreFocus?: () => void;
    } = $props();

    const {mails} = useRepositories();
</script>

<AttachmentList
        attachments={mail.attachments}
        download={(attachment, onProgress, signal) => mails.downloadAttachment(mail.id, attachment, onProgress, signal)}
        class={className}
        {onRestoreFocus}
/>
