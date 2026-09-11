<!--
    One row of the sidebar's view list: the link into the view, and the menu button that fades in
    over it while the row is hovered or focused.

    The `<li>` around it stays in `ViewList.svelte`: `animate:flip` only works on an element that
    is a direct child of the keyed each block, and the drag sits on the same element. Which is
    also why this is a `SidebarMenuButton` and not a `SidebarMenuEntry` -- the entry brings its
    own `<li>`, and an `<li>` in an `<li>` is not a list.
-->
<script lang="ts">
    import {DotsThreeVerticalIcon, ListIcon} from "phosphor-svelte";
    import {page} from "$app/state";
    import {Button} from "$lib/components/ui/button";
    import {SidebarMenuButton, SidebarMenuTrailing} from "$lib/components/ui/sidebar";
    import {viewHref} from "$lib/app/views/viewPath";
    import type {View} from "$lib/repository/ViewSocket";

    const {view, isActive}: {view: View; isActive: boolean} = $props();
</script>

<!-- pr-8 leaves the room the trailing slot overlays. -->
<SidebarMenuButton {isActive} class="pr-8">
    <!-- An anchor, so a view can be opened in a new tab and its address copied; the router
         handles the click. Not draggable itself: a link drags its url, and the row around it
         drags the view. -->
    {#snippet child({props})}
        <a href={viewHref(view.id, view.name, page.url)} draggable="false" {...props}>
            <ListIcon/>
            <span>{view.name}</span>
        </a>
    {/snippet}
</SidebarMenuButton>

<SidebarMenuTrailing>
    {#snippet hover()}
        <Button variant="ghost" size="icon-xs">
            <DotsThreeVerticalIcon/>
        </Button>
    {/snippet}
</SidebarMenuTrailing>
