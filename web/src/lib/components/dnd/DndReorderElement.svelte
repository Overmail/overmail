<!--
    One draggable row of a `DndReorderZone`.

    The whole row is what starts the drag, unless `handle` says otherwise -- then a
    `DndReorderHandle` somewhere inside it does, and the rest of the row keeps its own clicks.
-->
<script lang="ts" generics="T">
    import {setContext, type Snippet} from "svelte";
    import type {HTMLAttributes} from "svelte/elements";
    import type {DndReorder} from "$lib/hooks/dnd-reorder.svelte";
    import {DND_ITEM, type DndItemContext} from "./context";

    let {
        dnd,
        id,
        as = "div",
        handle = true,
        children,
        ...rest
    }: HTMLAttributes<HTMLElement> & {
        dnd: DndReorder<T>;
        /** The item's id, the same one `DndReorder`'s `id` hands out. */
        id: string;
        /** The tag to render, where a div is not what belongs there. */
        as?: string;
        /** Whether the row itself is the grip. False leaves that to a `DndReorderHandle`. */
        handle?: boolean;
        children?: Snippet;
    } = $props();

    setContext<DndItemContext>(DND_ITEM, {handleProps: () => dnd.handleProps(id)});

    /** Stands in for the row's own content while it is dragged, where the caller asked for one. */
    const Ghost = $derived(dnd.ghostComponent);
</script>

<svelte:element
        this={as}
        {...rest}
        {...dnd.itemProps(id)}
        {...handle ? dnd.handleProps(id) : {}}
        {@attach dnd.attach(id)}
>
    {#if Ghost !== null && dnd.isDragging(id)}
        <Ghost {id}/>
    {:else}
        {@render children?.()}
    {/if}
</svelte:element>
