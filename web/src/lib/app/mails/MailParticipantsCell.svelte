<script lang="ts">
    import type {MailParticipant} from "$lib/repository/MailRepository";

    let {participants}: { participants: MailParticipant[] } = $props();

    // The name if the mail carried one, the bare address otherwise: a column of addresses reads
    // far worse than a column of names, and the address is on the mail itself anyway.
    const label = $derived(participants.map((it) => it.name?.trim() || it.address).join(', '));
    const addresses = $derived(participants.map((it) => it.address).join(', '));
</script>

{#if participants.length === 0}
    <span class="text-muted-foreground">—</span>
{:else}
    <span class="block truncate" title={addresses}>{label}</span>
{/if}
