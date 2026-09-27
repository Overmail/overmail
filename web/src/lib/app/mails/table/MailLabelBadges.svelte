<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {cn} from "$lib/utils";
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";

    /** Past this the row is more badge than subject; the rest is a count. */
    const MAX_BADGES = 3;
    /** `gap-1` of the rows below. */
    const GAP_PX = 4;

    let {labels, size = "default"}: {
        labels: EmailLabel[];
        /** `sm` where the badges are beside the mail rather than the point of the row. */
        size?: "default" | "sm";
    } = $props();

    const candidates = $derived(labels.slice(0, MAX_BADGES));
    const small = $derived(size === "sm" && "h-4.5 px-1 text-xs");

    let width = $state(0);
    let measureRow = $state<HTMLElement>();
    let moreProbe = $state<HTMLElement>();
    let badgeWidths = $state<number[]>([]);
    let moreWidth = $state(0);

    // Read after every render of the measuring row, which is what changes with the labels and the size.
    $effect(() => {
        void candidates;
        void small;
        if (!measureRow || !moreProbe) return;
        badgeWidths = Array.from(measureRow.children)
            .slice(0, candidates.length)
            .map((badge) => (badge as HTMLElement).offsetWidth);
        moreWidth = moreProbe.offsetWidth;
    });

    /**
     * How many badges fit into the width with the count still beside them. Nothing measured yet
     * (the server render) shows them all, the same as a row wide enough for them.
     */
    const shownCount = $derived.by(() => {
        if (width === 0 || badgeWidths.length !== candidates.length) return candidates.length;
        let used = 0;
        for (let i = 0; i < badgeWidths.length; i++) {
            used += (i > 0 ? GAP_PX : 0) + badgeWidths[i];
            const needsCount = i + 1 < labels.length;
            if (used + (needsCount ? GAP_PX + moreWidth : 0) > width) return i;
        }
        return badgeWidths.length;
    });

    const shown = $derived(candidates.slice(0, shownCount));
    const hidden = $derived(labels.length - shown.length);
</script>

<!--
    Two rows over each other. The invisible one holds every badge the row would show with room to
    spare: it is what gives the element its natural width and height, and what the badges are
    measured on. The visible one sits on top and shows as many as the width the layout actually
    granted leaves room for, next to the count. `min-w-0`, so a tight row can shrink it at all.
-->
<div class="relative min-w-0 overflow-hidden" bind:clientWidth={width}>
    <div class="invisible flex flex-row items-center gap-1" aria-hidden="true" bind:this={measureRow}>
        {#each candidates as label (label.id)}
            <Badge variant="secondary" class={cn("shrink-0 font-normal", small)} color={label.color}>
                {label.name}
            </Badge>
        {/each}
        {#if labels.length > candidates.length}
            <Badge variant="outline" class={cn(small)}>+{labels.length - candidates.length}</Badge>
        {/if}
    </div>

    <!-- The widest the count can get, measured on its own so it adds nothing to the width. -->
    <div class="invisible absolute left-0 top-0" aria-hidden="true" bind:this={moreProbe}>
        <Badge variant="outline" class={cn(small)}>+{labels.length}</Badge>
    </div>

    <!-- The label's colour only identifies it; the Badge keeps it off the text so the name stays readable. -->
    <div class="absolute inset-0 flex flex-row items-center gap-1">
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
                    class={cn("shrink-0", small)}
                    title={labels.slice(shown.length).map((label) => label.name).join(", ")}
            >
                +{hidden}
            </Badge>
        {/if}
    </div>
</div>
