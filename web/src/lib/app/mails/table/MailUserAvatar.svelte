<script lang="ts">
    import {OvermailAvatar} from "$lib/components/avatar";
    import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";

    let {participant}: {participant: EmailParticipant} = $props();

    const displayName = $derived(participant.name ?? participant.address);
</script>

<!-- 24px, which is what holds a row within 32px. The picture travels with the mail's metadata,
     so there is nothing to look up here. -->
<OvermailAvatar
        url={participant.avatarUrl}
        name={displayName}
        class="size-6"
        fallbackClass="text-[10px]"
/>
