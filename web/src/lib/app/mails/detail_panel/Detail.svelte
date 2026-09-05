<!--
    Everything a mail is, without the frame around it: what it says, who it is between, and how it
    is sorted.

    Its own component because two places show it -- the panel beside the list and the mail's own
    page -- and only the bar above it differs between them.
-->
<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import Participants from "$lib/app/home/Participants.svelte";
    import Labels from "$lib/app/mails/detail_panel/Labels.svelte";
    import Content from "$lib/app/mails/detail_panel/Content.svelte";

    let {
        mail,
        showSubject = true,
    }: {
        mail: EmailMeta;
        /**
         * Whether the subject is part of this. The panel puts it above everything; the mail's own
         * page puts it in the line with the tools, where it is the heading of the page.
         */
        showSubject?: boolean;
    } = $props();

    const {mails} = useRepositories();
</script>

<div class="flex min-w-0 flex-col gap-6">
    {#if showSubject}
        <div class="px-6 font-display text-2xl text-pretty">
            {mail.subject}
        </div>
    {/if}

    <Participants
            from={mail.sender}
            to={mail.to}
            cc={mail.cc}
            bcc={mail.bcc}
            sentAt={new Date(mail.sent * 1000)}
    />

    <Labels
            labels={mail.labels}
            onAddLabel={(label) => mails.attachLabel(mail.id, label.id)}
            onCreateLabel={(name) => mails.createLabelOn(mail.id, name)}
            onRemoveLabel={(label) => mails.detachLabel(mail.id, label.id)}
    />

    <Content id={mail.id}/>
</div>
