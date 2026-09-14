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
    import {openViewId, VIEW_PARAM, viewUrl} from "$lib/app/views/viewPath";
    import {useRepositories} from "$lib/repository/repositories";
    import type {View} from "$lib/repository/ViewSocket";
    import {cn} from "$lib/utils";
    import DeleteViewDialog from "./DeleteViewDialog.svelte";
    import ViewListItem from "./ViewListItem.svelte";

    const {views} = useRepositories();

    // The socket is up while the sidebar is: the effect's teardown releases it.
    $effect(() => views.connect());

    /** Which view is open, which is what `?view=` says and nothing else. */
    const openId = $derived(openViewId(page.url));

    /** Guards the button while a request is on its way, so a double click adds one view. */
    let creating = $state(false);

    /**
     * Which view's name is being edited, if any.
     *
     * Here rather than in the row: one name is edited at a time, and the row has to stop being
     * draggable while it is -- a draggable element swallows the click that would put the caret
     * into the input, and text in it cannot be selected with the mouse at all.
     */
    let renamingId: string | null = $state(null);

    /** The view the delete is being confirmed for; null while nothing is. */
    let confirming: View | null = $state(null);

    // One zone: the views are a single list. The api asks for a neighbour rather than for a
    // position -- a neighbour is what survives a list that changed under the drag.
    const dnd = new DndReorder({
        zones: () => ({views: views.views}),
        id: (view) => view.id,
        onDrop: ({id, order}) => views.move(id, neighbourInOrder(order.views, id)),
    });

    function finishRename(view: View, name: string | null) {
        renamingId = null;
        // Null is the view keeping its name, see `renamedTo` -- nothing to ask the server for.
        if (name === null) return;

        // Not applied here: the new name arrives over the socket like every other change.
        views.update(view.id, {name}).catch((error) => console.error(error));
    }

    /**
     * Shift's way past the dialog, which is the same call without the question. Errors only reach
     * the console here -- the dialog is what has somewhere to show them, and this is the path that
     * was asked for without one.
     */
    async function deleteNow(view: View) {
        try {
            await views.remove(view.id);
            await leaveDeleted(view);
        } catch (error) {
            console.error(error);
        }
    }

    /**
     * Leaves the listing of a view that is gone: its `?view=` would otherwise stay in the url and
     * show a view nobody can open any more. Only that parameter goes, so an open mail stays open,
     * and as a replacement, so the back button does not lead into the deleted view.
     */
    async function leaveDeleted(view: View) {
        if (openId !== view.id) return;

        const url = new URL(page.url);
        url.searchParams.delete(VIEW_PARAM);
        await goto(`${url.pathname}${url.search}`, {replaceState: true});
    }

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
                                handle={renamingId !== view.id}
                                data-slot="sidebar-menu-item"
                                data-sidebar="menu-item"
                                class={cn("group/menu-item relative", dnd.isDragging(view.id) && "opacity-50")}
                        >
                            <ViewListItem
                                    {view}
                                    isActive={view.id === openId}
                                    renaming={renamingId === view.id}
                                    onRenameStart={() => (renamingId = view.id)}
                                    onRenameEnd={(name) => finishRename(view, name)}
                                    onDelete={(immediately) => {
                                        if (immediately) void deleteNow(view);
                                        else confirming = view;
                                    }}
                            />
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

<!-- One dialog for the whole list: which view it asks about is what opens it. -->
<DeleteViewDialog bind:view={confirming} onDeleted={leaveDeleted}/>
