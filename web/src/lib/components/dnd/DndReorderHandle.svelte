<!--
    The grip of a `DndReorderElement`: what is dragged, where the row itself must not be.

    Takes no id -- the row it sits in is the row it moves.
-->
<script lang="ts">
    import {getContext, type Snippet} from "svelte";
    import type {HTMLAttributes} from "svelte/elements";
    import {DND_ITEM, type DndItemContext} from "./context";

    let {
        as = "span",
        children,
        ...rest
    }: HTMLAttributes<HTMLElement> & {
        /** The tag to render, where a span is not what belongs there. */
        as?: string;
        children?: Snippet;
    } = $props();

    const item = getContext<DndItemContext>(DND_ITEM);
</script>

<svelte:element this={as} {...rest} {...item.handleProps()}>
    {@render children?.()}
</svelte:element>
