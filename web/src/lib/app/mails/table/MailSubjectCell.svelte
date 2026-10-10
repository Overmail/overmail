<script lang="ts">
    import {_} from "svelte-i18n";
    import {cn} from "$lib/utils";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import MailLabelBadges from "./MailLabelBadges.svelte";

    let {mail}: { mail: EmailMeta } = $props();

    const subject = $derived((mail.subject ?? "").trim());
</script>

<!-- The subject is the column that absorbs the leftover width, so the badges have room. -->
<div class="flex flex-row max-w-full items-center gap-2">
    <div class="size-1.5">
        {#if !mail.isRead}
            <div class="size-1.25 bg-blue-500 rounded-full"></div>
        {/if}
    </div>

    <div class="flex min-w-0 flex-1 flex-row gap-2">
        <span class={cn("truncate", !mail.isRead && "font-semibold", !mail.isRead && subject !== "" && "text-foreground/85")}>
            {subject || $_("mails.noSubject")}
        </span>

        <div class="text-muted-foreground/60 min-w-0 flex-1 truncate font-light text-ellipsis">
            {#if mail.preview}
                {mail.preview}
            {/if}
        </div>
    </div>

    <!-- At most 40% of the row; what does not fit in there becomes the count. -->
    <MailLabelBadges class="shrink-0" maxShare={0.4} labels={mail.labels}/>
</div>
