<script lang="ts">
    import * as Table from "$lib/components/ui/table";
    import KnowledgeRow from "$lib/app/settings/knowledge/KnowledgeRow.svelte";
    import {PINNED_LEFT_EDGE, PINNED_LEFT_EDGE_ON} from "$lib/app/settings/knowledge/pinnedColumn";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {createVirtualizer} from "$lib/hooks/virtualizer.svelte";
    import {cn} from "$lib/utils";
    import {_} from "svelte-i18n";

    let {
        rows,
        onedit,
        ondelete,
        onchanged,
    }: {
        rows: KnowledgeEntry[],
        onedit: (entry: KnowledgeEntry) => void,
        ondelete: (entry: KnowledgeEntry) => void,
        /** An entry a row saved by itself; see [KnowledgeRow]. */
        onchanged: (entry: KnowledgeEntry) => void,
    } = $props();

    /** How many columns the spacer rows have to span; the header below has the same four. */
    const COLUMN_COUNT = 4;

    /**
     * What a row is assumed to take before it has been measured: a name, a description clamped to
     * two lines, and the padding around them.
     *
     * Only a guess, and it does not have to be right -- every row that reaches the DOM reports its
     * real height back, see [measureRow]. What it decides is the scrollbar of a list that is
     * mostly still unmeasured, so being far off makes the scrollbar resize while one scrolls.
     */
    const ESTIMATED_ROW_HEIGHT = 65;

    /** How many rows above and below the viewport are kept in the DOM. */
    const OVERSCAN = 8;

    /**
     * The box that scrolls, in both directions.
     *
     * Its own rather than `Table.Root`'s: that wrapper carries fixed classes and no height, and
     * this table needs a bounded one -- the rows scroll inside the dialog instead of pushing it,
     * which is also what gives the header something to stick to. It is also the element the
     * virtualizer watches, so what it renders follows this box and not the page.
     */
    let scroller: HTMLDivElement | null = $state(null);
    let tableElement: HTMLTableElement | null = $state(null);
    let headerElement: HTMLTableSectionElement | null = $state(null);
    /** Whether anything is hidden past the left edge, which is what the name column marks. */
    let scrolledFromStart = $state(false);

    /**
     * How tall the sticky header is.
     *
     * The rows begin below it inside the same scroll box, so this is the distance between the
     * box's scroll position and the first row. Handed over as the virtualizer's `scrollMargin`:
     * without it every row is taken to sit a header's height higher than it does, and the window
     * it renders ends that far short at the bottom.
     */
    let headerHeight = $state(0);

    // Measured rather than assumed: a pinned column is only worth separating off while it is
    // covering something, and whether it is depends on the dialog's width and the keywords in it.
    $effect(() => {
        const box = scroller;
        const table = tableElement;
        if (!box || !table) return;

        const update = () => {
            scrolledFromStart = box.scrollLeft > 0;
        };
        update();

        box.addEventListener("scroll", update, {passive: true});
        // It also changes without anyone scrolling: the dialog resizes, and the table grows and
        // shrinks as entries are filtered, either of which can leave the scroll back at the start.
        const observer = new ResizeObserver(update);
        observer.observe(box);
        observer.observe(table);

        return () => {
            box.removeEventListener("scroll", update);
            observer.disconnect();
        };
    });

    // Observed rather than read once: the header wraps to two lines in a narrow dialog and in
    // languages with longer column names, and that moves where the rows start.
    $effect(() => {
        const header = headerElement;
        if (!header) return;

        const observer = new ResizeObserver(() => {
            headerHeight = header.getBoundingClientRect().height;
        });
        observer.observe(header);
        return () => observer.disconnect();
    });

    const virtualizer = createVirtualizer<HTMLDivElement, HTMLTableRowElement>(() => {
        // Read out here rather than inside the callbacks below: those are called by the
        // virtualizer, long after the effect that tracks these options has run. It is also what
        // makes this notice a saved entry -- the list hands over a new array with the same length.
        const box = scroller;
        const entries = rows;

        return {
            count: entries.length,
            getScrollElement: () => box,
            estimateSize: () => ESTIMATED_ROW_HEIGHT,
            overscan: OVERSCAN,
            // Keyed by the entry rather than by the position, so a measured height belongs to the
            // entry it was measured on and survives the list being patched around it.
            getItemKey: (index) => entries[index]?.id ?? index,
            scrollMargin: headerHeight,
        };
    });

    /**
     * The element of every row that is currently rendered.
     *
     * A plain Map and not state on purpose: nothing renders from it, it is only there so the
     * `bind:ref` below can be read back. Svelte asks a binding for its current value when the
     * element goes away and passes the null on only if the two still match -- which is how the
     * virtualizer hears about a row that has left. Making it reactive would instead re-run the
     * each block for every row that mounts.
     */
    const rowElements = new Map<string, HTMLTableRowElement>();

    /**
     * Hands a row's element to the virtualizer, which measures it and watches it from then on.
     *
     * Measuring is not optional here: the rows are not one line each. A description wraps, the
     * keywords wrap, and a row being edited grows a textarea as it is typed into -- all of which
     * happen long after the row was first laid out, and the resize observer the virtualizer
     * attaches to the element is what keeps the list's arithmetic in step with them.
     */
    function measureRow(id: string, node: HTMLTableRowElement | null) {
        if (node === null) rowElements.delete(id);
        else rowElements.set(id, node);

        // Null included -- that is what makes it forget the elements that are gone.
        virtualizer.virtualizer.measureElement(node);
    }

    /**
     * The rows on screen, with the entry each of them is about.
     *
     * Filtered against the current length: entries can disappear between the virtualizer
     * publishing its window and this reading it, and an index past the end is not a row.
     */
    const visible = $derived(
        virtualizer.items
            .filter((item) => item.index < rows.length)
            .map((item) => ({item, entry: rows[item.index]}))
    );

    // Spacer rows rather than absolutely positioned ones: a `<tr>` taken out of the flow loses the
    // table layout, and with it the column widths and the sticky cells that sit in them.
    //
    // The margin comes back off the item positions: those count from the top of the scroll box,
    // and what is padded here is the space inside the body, which starts below the header.
    const paddingTop = $derived((visible[0]?.item.start ?? headerHeight) - headerHeight);
    const paddingBottom = $derived(
        virtualizer.totalSize - ((visible.at(-1)?.item.end ?? headerHeight) - headerHeight)
    );
</script>

<div class="min-h-0 flex-1 overflow-hidden rounded-2xl border">
    <div bind:this={scroller} class="h-full overflow-auto">
        <table bind:this={tableElement} class="w-full caption-bottom text-sm">
            <Table.Header bind:ref={headerElement}>
                <Table.Row class="hover:bg-transparent">
                    <!--
                      Sticky in both directions: the header stays while the rows scroll under it,
                      and the name column stays while the keywords scroll past it. The corner
                      where the two meet is the one cell that has to sit above both.
                    -->
                    <Table.Head
                            class={cn(
                                "bg-popover sticky top-0 left-0 z-30 transition-shadow",
                                PINNED_LEFT_EDGE,
                                scrolledFromStart && PINNED_LEFT_EDGE_ON,
                            )}
                    >
                        {$_("settings.knowledge.list.columns.name")}
                    </Table.Head>
                    <Table.Head class="bg-popover sticky top-0 z-20">
                        {$_("settings.knowledge.list.columns.description")}
                    </Table.Head>
                    <Table.Head class="bg-popover sticky top-0 z-20">
                        {$_("settings.knowledge.list.columns.keywords")}
                    </Table.Head>
                    <!--
                      The actions column. Empty on purpose: the buttons come in with the pointer,
                      and a header is never hovered.
                    -->
                    <Table.Head class="bg-popover sticky top-0 right-0 z-30 w-24"></Table.Head>
                </Table.Row>
            </Table.Header>
            <Table.Body>
                <!-- The rows that are not rendered, as height. A bare row rather than a
                     Table.Row: it carries no border, no hover and nothing to read, it is the
                     space the list would have taken. A `<tr>` without a cell has no height. -->
                {#if paddingTop > 0}
                    <tr aria-hidden="true">
                        <td colspan={COLUMN_COUNT} class="p-0" style="height: {paddingTop}px"></td>
                    </tr>
                {/if}

                <!-- Keyed by the entry, not by the position: a row holds an open editor and its
                     own draft, and neither may be handed to the entry that took its place. -->
                {#each visible as {item, entry} (entry.id)}
                    <KnowledgeRow
                            bind:ref={() => rowElements.get(entry.id) ?? null, (node) => measureRow(entry.id, node)}
                            index={item.index}
                            {entry}
                            pinned={scrolledFromStart}
                            {onedit}
                            {ondelete}
                            {onchanged}
                    />
                {/each}

                {#if paddingBottom > 0}
                    <tr aria-hidden="true">
                        <td colspan={COLUMN_COUNT} class="p-0" style="height: {paddingBottom}px"></td>
                    </tr>
                {/if}
            </Table.Body>
        </table>
    </div>
</div>
