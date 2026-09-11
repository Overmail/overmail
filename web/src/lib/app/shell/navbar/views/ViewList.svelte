<!--
    The sidebar's views: what the user has, live, draggable into order, and the button that adds
    one.

    The list comes off the views socket, so a view another tab created, renamed or moved shows up
    here too -- and neither the create button nor a drag has to put the result on screen itself:
    they ask the server, and the row arrives the way every other change does.

    The drag is a `DndReorder`: the row itself moves rather than a line being drawn where it
    would land, and nothing is saved until the drop. A drag that is called off leaves the order
    as the server has it, which is what snaps the row back.
-->
<script lang="ts">
    import {PlusIcon} from "phosphor-svelte";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {
        SidebarGroupContent,
        SidebarMenu,
        SidebarMenuButton,
        SidebarMenuItem,
        SidebarMenuSkeleton,
    } from "$lib/components/ui/sidebar";
    import {DndReorderElement, DndReorderZone} from "$lib/components/dnd";
    import {DndReorder} from "$lib/hooks/dnd-reorder.svelte";
    import {neighbourInOrder} from "$lib/app/views/reorder";
    import {openViewId, viewUrl} from "$lib/app/views/viewPath";
    import {useRepositories} from "$lib/repository/repositories";
    import {cn} from "$lib/utils";
    import ViewListItem from "./ViewListItem.svelte";

    const {views} = useRepositories();

    // The socket is up while the sidebar is: the effect's teardown releases it.
    $effect(() => views.connect());

    /** Which view is open, which is what `?view=` says and nothing else. */
    const openId = $derived(openViewId(page.url));

    /** Guards the button while a request is on its way, so a double click adds one view. */
    let creating = $state(false);

    // One zone: the views are a single list. The api asks for a neighbour rather than for a
    // position -- a neighbour is what survives a list that changed under the drag.
    const dnd = new DndReorder({
        zones: () => ({views: views.views}),
        id: (view) => view.id,
        onDrop: ({id, order}) => views.move(id, neighbourInOrder(order.views, id)),
    });

    async function create() {
        if (creating) return;
        creating = true;
        try {
            const created = await views.create();
            // Straight into it: a view is created to be configured, and an empty one says
            // nothing until it is open. Through goto, not through an href -- which view it is
            // is only known once the server has answered.
            await goto(viewUrl(created.id, created.name, page.url));
        } catch (error) {
            console.error(error);
        } finally {
            creating = false;
        }
    }
</script>

<SidebarGroupContent>
    <DndReorderZone {dnd} id="views">
        <!-- The menu is the zone: it is the element the rows are in anyway. -->
        {#snippet child({props})}
            <SidebarMenu {...props}>
                {#if !views.hasLoaded}
                    <!-- One row, not an empty list: the socket answers in a moment, and a list
                         that fills in reads better than one that first says there is nothing. -->
                    <SidebarMenuItem>
                        <SidebarMenuSkeleton showIcon/>
                    </SidebarMenuItem>
                {:else}
                    {#each dnd.zones.views as view (view.id)}
                        <!-- What SidebarMenuItem renders, as an element: the row has to be the
                             one that is dragged, and that one is this component's. -->
                        <DndReorderElement
                                {dnd}
                                as="li"
                                id={view.id}
                                data-slot="sidebar-menu-item"
                                data-sidebar="menu-item"
                                class={cn("group/menu-item relative", dnd.isDragging(view.id) && "opacity-50")}
                        >
                            <ViewListItem {view} isActive={view.id === openId}/>
                        </DndReorderElement>
                    {/each}
                {/if}

                <SidebarMenuItem>
                    <!-- aria-disabled, not disabled: the button variant already dims and blocks
                         that, and it keeps the row in the tab order while the request is out. -->
                    <SidebarMenuButton onclick={create} aria-disabled={creating}>
                        <PlusIcon/>
                        <span>Neue Ansicht</span>
                    </SidebarMenuButton>
                </SidebarMenuItem>
            </SidebarMenu>
        {/snippet}
    </DndReorderZone>
</SidebarGroupContent>
