<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {cn} from "$lib/utils";
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";

    /** Past this the row is more badge than subject; the rest is a count. */
    const MAX_BADGES = 3;

    let {labels, size = "default"}: {
        labels: EmailLabel[];
        /** `sm` where the badges are beside the mail rather than the point of the row. */
        size?: "default" | "sm";
    } = $props();

    const shown = $derived(labels.slice(0, MAX_BADGES));
    const hidden = $derived(labels.length - shown.length);
    const small = $derived(size === "sm" && "h-4.5 px-1 text-xs");
</script>

<!-- The label's colour only identifies it; the Badge keeps it off the text so the name stays readable. -->
<div class="flex flex-row items-center gap-1">
    {#each shown as label (label.id)}
        <Badge
                variant="secondary"
                class={cn("shrink-0 font-normal", small)}
                color={label.color}
                title={label.description ?? label.name}
        >
            {label.name}
        </Badge>
    {/each}

    {#if hidden > 0}
        <Badge
                variant="outline"
                class={cn(small)}
        >
            +{hidden}
        </Badge>
    {/if}
</div>
