<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";

    /** Past this the row is more badge than subject; the rest is a count. */
    const MAX_BADGES = 3;

    let {labels}: {labels: EmailLabel[]} = $props();

    const shown = $derived(labels.slice(0, MAX_BADGES));
    const hidden = $derived(labels.length - shown.length);
</script>

<!-- The label's colour only identifies it; the Badge keeps it off the text so the name stays readable. -->
<div class="flex flex-row items-center gap-1">
    {#each shown as label (label.id)}
        <Badge
                variant="secondary"
                class="shrink-0 font-normal"
                color={label.color}
                title={label.description ?? label.name}
        >
            {label.name}
        </Badge>
    {/each}

    {#if hidden > 0}
        <Badge
                variant="outline"
        >
            +{hidden}
        </Badge>
    {/if}
</div>
