<script lang="ts">
    import type {Mail} from "$lib/repository/MailRepository";
    import {displayNameOf} from "../participant";
    import MailUserAvatar from "./MailUserAvatar.svelte";

    let {mail}: { mail: Mail } = $props();

    /** Only the name is shown; the address stands in when the header carried no name. */
    const displayName = $derived(displayNameOf(mail.sender));
</script>

<div class="flex items-center gap-2.5" title={mail.sender.address}>
    <MailUserAvatar participant={mail.sender} />

    <!-- min-w-0 lets the flex child shrink, without it truncate never kicks in. -->
    <span class="min-w-0 truncate">{displayName}</span>
</div>
