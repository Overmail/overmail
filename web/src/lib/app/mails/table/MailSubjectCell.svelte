<script lang="ts">
    import {_} from "svelte-i18n";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import MailLabelBadges from "./MailLabelBadges.svelte";

    let {mail}: {mail: EmailMeta} = $props();

    const subject = $derived(mail.subject.trim());
</script>

<!-- The subject is the column that absorbs the leftover width, so the badges have room. -->
<div class="flex min-w-0 items-center gap-2">
    <span class="truncate" class:text-muted-foreground={subject === ""} class:font-medium={!mail.isRead}>
        {subject || $_("mails.table.noSubject")}
    </span>
    <MailLabelBadges labels={mail.labels}/>
</div>
