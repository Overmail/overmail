<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import MailUserAvatar from "./MailUserAvatar.svelte";

    let {mail}: {mail: EmailMeta} = $props();

    /** Only the name is shown; the address stands in when the header carried no name. */
    const displayName = $derived(mail.sender.name ?? mail.sender.address);
</script>

<div class="flex items-center gap-2.5" title={mail.sender.address}>
    <MailUserAvatar participant={mail.sender}/>

    <!-- min-w-0 lets the flex child shrink, without it truncate never kicks in. -->
    <span class="min-w-0 truncate" class:font-medium={!mail.isRead}>{displayName}</span>
</div>
