<script lang="ts">
    import {cn} from "$lib/utils";
    import {displayName} from "$lib/app/mails/participants";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import MailUserAvatar from "./MailUserAvatar.svelte";

    let {mail}: {mail: EmailMeta} = $props();

    /** Only the name is shown; the address stands in when the header carried no name. */
    const sender = $derived(displayName(mail.sender));
</script>

<div class="flex items-center gap-2.5" title={mail.sender.address}>
    <MailUserAvatar participant={mail.sender}/>

    <!-- min-w-0 lets the flex child shrink, without it truncate never kicks in. -->
    <!-- Unread is the one thing that pulls out of the table's muted body, and only just: the
         weight already carries it, the colour needs no more than a step. -->
    <span class={cn("min-w-0 truncate", !mail.isRead && "text-foreground/85 font-medium")}>{sender}</span>
</div>
