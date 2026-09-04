<script lang="ts">
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";
    import {Badge} from "$lib/components/ui/badge";

    let {
        labels,
    }: {
        labels: EmailLabel[],
    } = $props();
</script>

<div class="flex flex-row flex-wrap gap-1 px-6">
    {#each labels as label (label.id)}
        <Badge
                variant="secondary"
                class="shrink-0 font-normal"
                color={label.color}
                title={label.description ?? label.name}
        >
            {label.name}
        </Badge>
    {/each}

    <Badge
            variant="outline"
            class="shrink-0 font-normal cursor-pointer"
    >+</Badge>
</div>