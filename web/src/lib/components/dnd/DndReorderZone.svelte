<!--
    One list of a `DndReorder`: what is dragged over and dropped on.

    The zone, not the row, listens -- that covers the gaps between rows, and it keeps an emptied
    zone reachable, which is the only way anything gets back into it.
-->
<script lang="ts" generics="T">
    import type {Snippet} from "svelte";
    import type {HTMLAttributes} from "svelte/elements";
    import type {DndReorder} from "$lib/hooks/dnd-reorder.svelte";

    let {
        dnd,
        id,
        as = "div",
        children,
        child,
        ...rest
    }: HTMLAttributes<HTMLElement> & {
        dnd: DndReorder<T>;
        /** The zone's name -- what the order is keyed by and what `onDrop` reports. */
        id: string;
        /** The tag to render, where a div is not what belongs there. */
        as?: string;
        children?: Snippet;
        /** Puts the zone's props on an element of the caller's instead of on one of ours. */
        child?: Snippet<[{props: Record<string, unknown>}]>;
    } = $props();

    const zoneProps = $derived({...rest, ...dnd.zoneProps(id)});
</script>

{#if child}
    {@render child({props: zoneProps})}
{:else}
    <svelte:element this={as} {...zoneProps}>
        {@render children?.()}
    </svelte:element>
{/if}
