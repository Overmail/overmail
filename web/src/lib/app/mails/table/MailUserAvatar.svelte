<script lang="ts">
    import {OvermailAvatar} from "$lib/components/avatar";
    import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";

    let {participant}: {participant: EmailParticipant} = $props();

    const displayName = $derived(participant.name ?? participant.address);
</script>

<!-- Small on purpose: a row is one line, and the face is what it is next to rather than what it
     is about. The picture travels with the mail's metadata, so there is nothing to look up. -->
<OvermailAvatar
        url={participant.avatarUrl}
        name={displayName}
        class="size-5"
        fallbackClass="text-[9px]"
/>
