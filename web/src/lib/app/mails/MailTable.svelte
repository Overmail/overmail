<script lang="ts">
    import {FlexRender, createTable} from "@tanstack/svelte-table";
    import * as Table from "$lib/components/ui/table";
    import {Button} from "$lib/components/ui/button";
    import {createVirtualizer} from "$lib/hooks/virtualizer.svelte";
    import {goto} from "$app/navigation";
    import {cn} from "$lib/utils";
    import {COLUMN_WIDTHS, GHOST_SHAPES, columns, features, spansRow} from "./columns";
    import {mailGrouping} from "./grouping.svelte";
    import {buildThreadRows, mailRow, type MailTableRow} from "./rows";
    import type {ThreadedMailStore} from "./ThreadedMailStore.svelte";
    import GroupByThreadToggle from "./GroupByThreadToggle.svelte";
    import MailsEmpty from "./MailsEmpty.svelte";
    import MailGhostCell from "./table/MailGhostCell.svelte";
    import type {MailStore} from "./MailStore.svelte";

    /**
     * Rows cap at 32px -- the cell height is what a table row follows, see `cellClass` below. Not
     * an estimate but the height: every row is one clipped line, and the scrollbar is sized from
     * this number.
     */
    const ROW_HEIGHT = 32;

    /**
     * Rows before the end of what is held at which the next page is asked for. A page is a hundred
     * mails, so this is short: it only has to be further than one flick of a scroll wheel.
     */
    const PREFETCH_ROWS = 20;

    /**
     * Stand-ins for the length nobody knows yet. Before the first page there would otherwise be no
     * rows at all and the empty state would flash.
     */
    const PLACEHOLDER_ROWS = 8;

    let {store, threaded}: { store: MailStore; threaded: ThreadedMailStore } = $props();

    /** Which of the two arrangements the list is in. They page in completely different ways. */
    const grouped = $derived(mailGrouping.byThread);

    let viewport = $state<HTMLDivElement | null>(null);

    /**
     * The rows the table has data for. Only the rows, never the layout: grouped, where a mail sits
     * comes from the thread skeleton, and a row is looked up by the id that layout names.
     */
    const dataRows = $derived<MailTableRow[]>(
        grouped
            ? buildThreadRows(
                  threaded.threads,
                  (id) => threaded.mails[id],
                  threaded.unfiled.loaded,
                  threaded.unfiled.total
              )
            : store.loaded.map(mailRow)
    );

    const table = createTable({
        features,
        columns,
        get data() {
            return dataRows;
        },
        getRowId: (row) => row.id
    });

    const modelRows = $derived(table.getRowModel().rows);

    // Rows are looked up by id rather than by position: ungrouped, a mail's place in the row model
    // says nothing about its place in the mailbox, because the mailbox is filled from both ends.
    const rowsById = $derived(new Map(modelRows.map((row) => [row.id, row])));

    /** Opens one mail on its own screen. */
    function open(id: string): void {
        void goto(`/email/${id}`);
    }

    /**
     * How long the list is, not how much of it is loaded. Exact either way: ungrouped the server
     * counts the mailbox, grouped the thread skeleton names every row before a single mail of it
     * has been fetched.
     */
    const rowCount = $derived(grouped ? threaded.rowCount : store.total);

    /** Whether the length is known at all yet. Before that a handful of rows stand in for it. */
    const isInitialized = $derived(grouped ? threaded.initialized : store.initialized);

    /** Enough rows to fill a screen while the real length is unknown. */
    const visibleRowCount = $derived(rowCount === 0 && !isInitialized ? PLACEHOLDER_ROWS : rowCount);

    const virtualizer = createVirtualizer<HTMLDivElement, HTMLTableRowElement>(() => {
        // Read out here rather than only inside `getScrollElement`: that closure is called by the
        // virtualizer, long after the effect that tracks these options has run, so reading it
        // there would not make the binding of the element reach the virtualizer.
        const scrollElement = viewport;

        return {
            count: visibleRowCount,
            getScrollElement: () => scrollElement,
            estimateSize: () => ROW_HEIGHT,
            overscan: 12
        };
    });

    /** The id of the row at an index of the list, or undefined while that mail is not held. */
    function rowIdAt(index: number): string | undefined {
        return grouped ? threaded.rowIdAt(index) : store.entries[index]?.id;
    }

    // A `row` of undefined is a placeholder: the index is inside the list but its page has not been
    // fetched. The filter drops indexes the list has since lost, which happens when the length
    // shrinks between the virtualizer's last range calculation and this render.
    const visible = $derived(
        virtualizer.items
            .filter((item) => item.index < visibleRowCount)
            .map((item) => {
                const id = rowIdAt(item.index);
                return {item, row: id === undefined ? undefined : rowsById.get(id)};
            })
    );

    // Which slice of the list is on screen, for debugging. `virtualizer.range` is the part that
    // is really in the viewport; `visible` also carries the overscan rows around it.
    $effect(() => {
        if (!import.meta.env.DEV) return;
        if (visible.length === 0) return;

        const range = virtualizer.virtualizer.range;
        const onScreen = range
            ? visible.filter(({item}) => item.index >= range.startIndex && item.index <= range.endIndex)
            : visible;

        const first = onScreen[0];
        const last = onScreen.at(-1);
        if (!first || !last) return;

        // "pending" for an index the list has but does not hold yet.
        const id = (entry: (typeof visible)[number]) => entry.row?.id ?? 'pending';

        console.log(
            `[MailTable] rows ${first.item.index}\u2013${last.item.index}` +
            ` of ${store.loaded.length} loaded / ${rowCount} total` +
            `${grouped ? ` (grouped, ${threaded.threads.length} threads)` : ''}` +
            ` \u2192 ${id(first)} \u2026 ${id(last)}` +
            ` (rendered incl. overscan ${visible[0].item.index}\u2013${visible.at(-1)!.item.index})`
        );
    });

    // Spacer rows rather than absolutely positioned ones: a `<tr>` taken out of the flow loses the
    // table layout, and with it the column widths the sticky header lines up with.
    const paddingTop = $derived(visible[0]?.item.start ?? 0);
    const paddingBottom = $derived(virtualizer.totalSize - (visible.at(-1)?.item.end ?? 0));

    // Deliberately reads no status: the store writes one, and an effect that reacts to its own
    // write would run again for every step a request takes. What it reads is what a scroll
    // changes, which is exactly when the next page is due. After a failure it is the scroll or the
    // retry button that asks again.
    $effect(() => {
        const source = grouped ? threaded : store;

        // Nothing is on screen before the first answer, and it is that answer which gives the list
        // its length -- so ask for the start of it and the rest follows from the scrolling.
        if (rowCount === 0) {
            source.ensureRange(0, 0);
            return;
        }

        const first = visible[0]?.item.index;
        const last = visible.at(-1)?.item.index;
        if (first === undefined || last === undefined) return;

        // Placeholders on screen name exactly which mails are missing -- grouped by id off the
        // thread skeleton, ungrouped by position. Either way this fires again as answers land
        // until the range is covered.
        source.ensureRange(first - PREFETCH_ROWS, last + PREFETCH_ROWS);
    });

    const hasFailed = $derived(
        grouped ? threaded.failed || threaded.unfiled.status === 'error' : store.status === 'error'
    );

    // A header reads as the group it opens; its mails are stepped in under it.
    const rowClass = (row: MailTableRow) => {
        if (row.kind === 'group') return 'bg-muted/40';
        if (row.grouped) return '[&>td:first-child]:pl-8';
        return '';
    };
</script>

<section class="flex flex-col gap-3">
    <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="flex items-baseline gap-2">
            <h2 class="text-sm font-medium">Alle Mails</h2>
            <span class="text-muted-foreground text-xs tabular-nums">
                {#if grouped}
                    {threaded.threads.length}
                    {threaded.threads.length === 1 ? 'Thread' : 'Threads'}
                {:else}
                    {store.loaded.length} von {store.total}
                {/if}
            </span>
        </div>
        <GroupByThreadToggle />
    </div>

    <!-- The scroll container has to be this one and not the table's own wrapper: the sticky header
         sticks to its nearest scrolling ancestor, and the wrapper never scrolls. -->
    <div
            bind:this={viewport}
            class="h-[70vh] overflow-auto rounded-md border [&>[data-slot=table-container]]:overflow-visible"
    >
        <!-- The row count is stated for a screen reader, because the DOM no longer carries it: a
             windowed table holds the rows near the viewport and nothing else. -->
        <Table.Root class="min-w-[52rem] table-fixed" aria-rowcount={rowCount}>
            <colgroup>
                {#each columns as column (column.id)}
                    {@const width = COLUMN_WIDTHS[column.id ?? '']}
                    <col style={width ? `width: ${width}` : undefined} />
                {/each}
            </colgroup>

            <Table.Header class="sticky top-0 z-10">
                {#each table.getHeaderGroups() as headerGroup (headerGroup.id)}
                    <Table.Row class="hover:bg-transparent">
                        {#each headerGroup.headers as header (header.id)}
                            <!-- The heads carry their own bottom line as a shadow: the row's border
                                 belongs to the table and is left behind once the cells lift out of
                                 the flow. The background is on the cells, not the row -- rows
                                 scroll under a sticky `thead` and a transparent one lets them
                                 show through. -->
                            <Table.Head
                                    class="font-heading bg-background h-8 py-0 text-xs shadow-[inset_0_-1px_0_var(--border)]"
                            >
                                {#if !header.isPlaceholder}
                                    <FlexRender {header} />
                                {/if}
                            </Table.Head>
                        {/each}
                    </Table.Row>
                {/each}
            </Table.Header>

            <Table.Body>
                <!-- The rows that are not rendered, as height. A bare row rather than a Table.Row:
                     it carries no border, no hover and nothing to read, it is the space the list
                     would have taken. A `<tr>` without a cell has no height to give. -->
                {#if paddingTop > 0}
                    <tr aria-hidden="true">
                        <td colspan={columns.length} class="p-0" style="height: {paddingTop}px"></td>
                    </tr>
                {/if}

                {#each visible as {item, row} (row?.id ?? `pending-${item.index}`)}
                    {#if row}
                        {@const openable = row.original.kind === 'mail' ? row.original.mail.id : null}
                        <!-- The whole row opens the mail: a link in one cell would be a target the
                             width of a subject, and the row is what a reader aims at. Rows that are
                             a group heading open nothing. -->
                        <Table.Row
                                aria-rowindex={item.index + 2}
                                class={cn('h-8', openable && 'cursor-pointer', rowClass(row.original))}
                                role={openable ? 'link' : undefined}
                                tabindex={openable ? 0 : undefined}
                                onclick={openable ? () => open(openable) : undefined}
                                onkeydown={openable
                                    ? (event: KeyboardEvent) => {
                                          if (event.key !== 'Enter' && event.key !== ' ') return;
                                          event.preventDefault();
                                          open(openable);
                                      }
                                    : undefined}
                        >
                            {#if spansRow(row.original)}
                                <Table.Cell colspan={columns.length} class="h-8 py-0">
                                    <FlexRender cell={row.getAllCells()[0]} />
                                </Table.Cell>
                            {:else}
                                {#each row.getAllCells() as cell (cell.id)}
                                    <Table.Cell class="h-8 overflow-hidden py-0">
                                        <FlexRender {cell} />
                                    </Table.Cell>
                                {/each}
                            {/if}
                        </Table.Row>
                    {:else}
                        <!-- A mail the list has and does not hold yet: the shape of the row
                             without the content, so nothing shifts when it arrives. -->
                        <Table.Row aria-rowindex={item.index + 2} class="h-8 hover:bg-transparent">
                            {#each columns as column (column.id)}
                                {@const ghost = GHOST_SHAPES[column.id ?? '']}
                                <Table.Cell class="h-8 overflow-hidden py-0">
                                    {#if ghost}
                                        <MailGhostCell widthClass={ghost.width} withAvatar={ghost.withAvatar} />
                                    {/if}
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {/if}
                {/each}

                {#if paddingBottom > 0}
                    <tr aria-hidden="true">
                        <td colspan={columns.length} class="p-0" style="height: {paddingBottom}px"></td>
                    </tr>
                {/if}

                {#if visibleRowCount === 0}
                    <Table.Row class="hover:bg-transparent">
                        <Table.Cell colspan={columns.length} class="h-24 text-center">
                            <MailsEmpty />
                        </Table.Cell>
                    </Table.Row>
                {/if}
            </Table.Body>
        </Table.Root>
    </div>

    {#if hasFailed}
        <div class="flex items-center gap-3">
            <p class="text-destructive text-xs">Weitere Mails konnten nicht geladen werden.</p>
            <Button
                    variant="outline"
                    size="sm"
                    onclick={() => (grouped ? threaded.retry() : store.retry())}
            >
                Erneut versuchen
            </Button>
        </div>
    {/if}
</section>
