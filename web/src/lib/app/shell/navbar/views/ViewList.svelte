<!--
    The sidebar's views: what the user has, live, draggable into order, and the button that adds
    one.

    The list comes off the views socket, so a view another tab created, renamed or moved shows up
    here too -- and neither the create button nor a drag has to put the result on screen itself:
    they ask the server, and the row arrives the way every other change does.

    A drag moves the row itself rather than drawing a line where it would land, the same way
    `routes/views/+page.svelte` reorders its categories: the list under the cursor is already the
    answer. Nothing is saved until the drop -- a drag that is called off leaves the order as the
    server has it, which is what snaps the row back.

    Reordering is pointer-only for now; there is no keyboard way to move a row.
-->
<script lang="ts">
    import {PlusIcon} from "phosphor-svelte";
    import {flip} from "svelte/animate";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {
        SidebarGroupContent,
        SidebarMenu,
        SidebarMenuButton,
        SidebarMenuItem,
        SidebarMenuSkeleton,
    } from "$lib/components/ui/sidebar";
    import {neighbourInOrder} from "$lib/app/views/reorder";
    import {openViewId, viewUrl} from "$lib/app/views/viewPath";
    import {useRepositories} from "$lib/repository/repositories";
    import ViewListItem from "./ViewListItem.svelte";

    /** Long enough to follow a row to its new place, short enough not to hold up the next drag. */
    const FLIP_MS = 150;

    /** A 1x1 transparent gif. */
    const TRANSPARENT_PIXEL = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";

    /**
     * What the browser drags along under the cursor instead of a copy of the row: nothing. The
     * row itself is already moving through the list, and a ghost of it beside the cursor says the
     * same thing a second time.
     *
     * Built once, not per drag: Firefox drags nothing at all when the image it is handed has not
     * been decoded by the time the drag starts. Null while rendering on the server, where there
     * is no `Image`.
     */
    const dragImage = (() => {
        if (typeof Image === "undefined") return null;

        const image = new Image();
        image.src = TRANSPARENT_PIXEL;

        return image;
    })();

    const {views} = useRepositories();

    // The socket is up while the sidebar is: the effect's teardown releases it.
    $effect(() => views.connect());

    /** Which view is open, which is what `?view=` says and nothing else. */
    const openId = $derived(openViewId(page.url));

    /** Guards the button while a request is on its way, so a double click adds one view. */
    let creating = $state(false);

    /** The view being dragged, or null. Also what dims its row while it is in flight. */
    let draggingId = $state<string | null>(null);

    /**
     * The ids in the order the drag has put them, or null when nothing is being dragged.
     *
     * The drag's own copy: it is what the rows are rendered from while it lasts, and dropping it
     * is what gives the server's order back -- see [onDragEnd].
     */
    let dragOrder = $state<string[] | null>(null);

    /**
     * When the rows have finished flipping. Mid-flight `getBoundingClientRect` reports positions
     * in transit, and a slot computed from those would put the row somewhere else again.
     */
    let settledAt = 0;

    /** What is rendered: the drag's order while there is one, the server's otherwise. */
    const rows = $derived.by(() => {
        const order = dragOrder;
        if (order === null) return views.views;

        const dragged = order
            .map((id) => views.views.find((view) => view.id === id))
            .filter((view) => view !== undefined);
        // A view that arrived mid-drag -- another tab created one -- goes to the end rather than
        // being left out of the list until the drag is over.
        const arrived = views.views.filter((view) => !order.includes(view.id));

        return [...dragged, ...arrived];
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

    function onDragStart(event: DragEvent, id: string) {
        draggingId = id;
        dragOrder = views.views.map((view) => view.id);
        settledAt = 0;
        // Firefox starts no drag without a payload.
        event.dataTransfer?.setData("text/plain", id);
        if (event.dataTransfer) {
            event.dataTransfer.effectAllowed = "move";
            if (dragImage !== null) event.dataTransfer.setDragImage(dragImage, 0, 0);
        }
    }

    /**
     * The slot is how many of the other rows have their midpoint above the cursor -- monotonic in
     * `clientY`, so two neighbours agree on their shared edge and the list does not flicker
     * there. Once the row has moved the cursor sits over it, which is a full row of hysteresis.
     *
     * Listened for on the list rather than on each row, so the gap between two rows is covered
     * as well.
     */
    function onDragOver(event: DragEvent & {currentTarget: HTMLElement}) {
        // Without this the browser refuses the drop and the row springs back.
        event.preventDefault();
        if (event.dataTransfer) event.dataTransfer.dropEffect = "move";

        if (draggingId === null || dragOrder === null) return;
        if (performance.now() < settledAt) return;

        let slot = 0;
        for (const element of event.currentTarget.querySelectorAll<HTMLElement>("[data-view-id]")) {
            if (element.dataset.viewId === draggingId) continue;
            const rect = element.getBoundingClientRect();
            if (event.clientY > rect.top + rect.height / 2) slot++;
        }

        moveTo(slot);
    }

    /** Puts the dragged row at [slot], counted among the rows it is not one of. */
    function moveTo(slot: number) {
        const order = dragOrder;
        const id = draggingId;
        if (order === null || id === null) return;

        const from = order.indexOf(id);
        // Taking it out at `from` and putting it back there rebuilds the same list.
        if (from === -1 || from === slot) return;

        const next = [...order];
        next.splice(from, 1);
        next.splice(slot, 0, id);

        dragOrder = next;
        settledAt = performance.now() + FLIP_MS;
    }

    /** The drop is the save: what the list reads as now is what the server is told. */
    async function onDrop(event: DragEvent) {
        event.preventDefault();

        const id = draggingId;
        const order = dragOrder;
        draggingId = null;
        dragOrder = null;
        if (id === null || order === null) return;

        const afterId = neighbourInOrder(order, id);
        // Dropped where it already was: nothing to write.
        if (afterId === neighbourInOrder(views.views.map((view) => view.id), id)) return;

        try {
            await views.move(id, afterId);
        } catch (error) {
            console.error(error);
        }
    }

    /** A drag that ended anywhere else: the server's order is what the list goes back to. */
    function onDragEnd() {
        draggingId = null;
        dragOrder = null;
    }
</script>

<SidebarGroupContent>
    <SidebarMenu ondragover={onDragOver} ondrop={onDrop}>
        {#if !views.hasLoaded}
            <!-- One row, not an empty list: the socket answers in a moment, and a list that
                 fills in reads better than one that first says there is nothing. -->
            <SidebarMenuItem>
                <SidebarMenuSkeleton showIcon/>
            </SidebarMenuItem>
        {:else}
            {#each rows as view (view.id)}
                <!-- What SidebarMenuItem renders, as an element: `animate:flip` is what follows
                     a row to its new place, and it cannot be put on a component. -->
                <li
                    data-slot="sidebar-menu-item"
                    data-sidebar="menu-item"
                    data-view-id={view.id}
                    class="group/menu-item relative"
                    class:opacity-50={draggingId === view.id}
                    draggable="true"
                    animate:flip={{duration: FLIP_MS}}
                    ondragstart={(event) => onDragStart(event, view.id)}
                    ondragend={onDragEnd}
                >
                    <ViewListItem {view} isActive={view.id === openId}/>
                </li>
            {/each}
        {/if}

        <SidebarMenuItem>
            <!-- aria-disabled, not disabled: the button variant already dims and blocks that,
                 and it keeps the row in the tab order while the request is out. -->
            <SidebarMenuButton onclick={create} aria-disabled={creating}>
                <PlusIcon/>
                <span>Neue Ansicht</span>
            </SidebarMenuButton>
        </SidebarMenuItem>
    </SidebarMenu>
</SidebarGroupContent>
