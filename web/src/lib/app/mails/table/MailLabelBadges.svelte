<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";

    /** Past this the row is more badge than subject; the rest is a count. */
    const MAX_BADGES = 3;

    let {labels}: {labels: EmailLabel[]} = $props();

    const shown = $derived(labels.slice(0, MAX_BADGES));
    const hidden = $derived(labels.length - shown.length);
</script>

<!--
    The label's own colour, mixed into the surface rather than used as one: it is picked from the
    label's name and knows nothing about the theme, so a flat fill would be unreadable in one of
    them. The text carries the hue, the background only hints at it.
-->
{#each shown as label (label.id)}
    <Badge
            variant="secondary"
            class="shrink-0 bg-(--label)/10 font-normal text-(--label)"
            style="--label: {label.color}"
            title={label.description ?? label.name}
    >
        {label.name}
    </Badge>
{/each}

{#if hidden > 0}
    <span class="text-muted-foreground shrink-0 text-xs" title={labels.map((it) => it.name).join(", ")}>
        +{hidden}
    </span>
{/if}
