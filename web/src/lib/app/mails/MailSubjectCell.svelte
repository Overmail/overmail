<script lang="ts">
    let {subject}: { subject: string } = $props();

    const trimmed = $derived(subject.trim());
</script>

{#if trimmed === ''}
    <span class="text-muted-foreground italic">Kein Betreff</span>
{:else}
    <span class="block truncate font-medium" title={trimmed}>{trimmed}</span>
{/if}
