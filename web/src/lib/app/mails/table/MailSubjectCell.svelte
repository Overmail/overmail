<script lang="ts">
    import {_} from "svelte-i18n";
    import {cn} from "$lib/utils";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import MailLabelBadges from "./MailLabelBadges.svelte";

    let {mail}: {mail: EmailMeta} = $props();

    const subject = $derived(mail.subject.trim());
</script>

<!-- The subject is the column that absorbs the leftover width, so the badges have room. -->
<div class="flex flex-row min-w-0 items-center gap-2">
    <div class="size-1.5">
        {#if !mail.isRead}
            <div class="size-1.25 bg-blue-500 rounded-full"></div>
        {/if}
    </div>
    <!-- The muted tone is the table's, so only unread pulls out of it. A mail without a subject
         stays back even then: the stand-in is not what the mail says. -->
    <span class={cn("truncate", !mail.isRead && "font-medium", !mail.isRead && subject !== "" && "text-foreground/85")}>
        {subject || $_("mails.table.noSubject")}
    </span>

    <!-- How the mail begins, cut to one line by the server. Not there for a mail whose body
         nothing has looked at yet, and empty for one with nothing readable in it -- either way
         the row is the subject alone, which is what it was before. -->
    {#if mail.preview}
        <div class="text-muted-foreground min-w-0 flex-1 truncate font-light">{mail.preview}</div>
    {/if}

    <MailLabelBadges labels={mail.labels}/>
</div>
