<script lang="ts">
    import {FlexRender, createTable} from "@tanstack/svelte-table";
    import * as Table from "$lib/components/ui/table";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {createVirtualizer} from "$lib/hooks/virtualizer.svelte";
    import {cn} from "$lib/utils";
    import {COLUMN_WIDTHS, columns, features} from "./columns";
    import type {MailStore} from "./MailStore.svelte";

    /**
     * Every row is one clipped line, so this is not an estimate but the height. Keep it in step
     * with the `h-12` on the rows below -- the scrollbar is sized from it.
     */
    const ROW_HEIGHT = 48;

    /** How far past the visible rows the store is asked to have mails ready. */
    const PREFETCH_ROWS = 20;

    let {store}: { store: MailStore } = $props();

    let viewport = $state<HTMLDivElement | null>(null);

    const table = createTable({
        features,
        columns,
        get data() {
            return store.loaded;
        },
        // Ids from the mail itself, so a row keeps its identity when the list is reordered by an
        // upsert rather than being rebuilt from its position.
        getRowId: (mail) => mail.id
    });

    const rows = $derived(table.getRowModel().rows);
    const tableColumns = $derived(table.getAllColumns());

    // The row model only holds what is loaded, and in mailbox order -- but the mailbox is filled
    // from both ends, so a row's place in it says nothing about its position. Mails are looked up
    // by index in the store and turned into rows through this.
    const rowsById = $derived(new Map(rows.map((row) => [row.id, row])));

    /**
     * How long the list is, not how much of it is loaded. The server reports the mailbox size with
     * the first page, so the scrollbar is the right length from then on and every index without a
     * mail behind it yet is drawn as a placeholder -- scrolling onto one is what fetches it.
     */
    const rowCount = $derived(store.total);

    const virtualizer = createVirtualizer<HTMLDivElement, HTMLTableRowElement>(() => {
        // Read out here rather than only inside `getScrollElement`: that closure is called by the
        // virtualizer, long after the effect that tracks these options has run, so reading it
        // there would not make the binding of the element reach the virtualizer.
        const scrollElement = viewport;

        return {
            count: rowCount,
            getScrollElement: () => scrollElement,
            estimateSize: () => ROW_HEIGHT,
            overscan: 12
        };
    });

    // A `row` of undefined is a placeholder: the index is inside the mailbox but its page has not
    // been fetched. The filter drops indexes the list has since lost, which happens when a removal
    // lands between the virtualizer's last range calculation and this render.
    const visible = $derived(
        virtualizer.items
            .filter((item) => item.index < rowCount)
            .map((item) => {
                const mail = store.entries[item.index];
                return {item, row: mail === undefined ? undefined : rowsById.get(mail.id)};
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

        // "pending" for an index the mailbox has but this page has not fetched yet.
        const id = (entry: (typeof visible)[number]) => entry.row?.id ?? 'pending';

        console.log(
            `[MailTable] rows ${first.item.index}\u2013${last.item.index}` +
            ` of ${store.loaded.length} loaded / ${rowCount} total` +
            ` \u2192 ids ${id(first)} \u2026 ${id(last)}` +
            ` (rendered incl. overscan ${visible[0].item.index}\u2013${visible.at(-1)!.item.index})`
        );
    });

    // Spacer rows rather than absolutely positioned ones: a `<tr>` taken out of the flow loses the
    // table layout, and with it the column widths the sticky header lines up with.
    const paddingTop = $derived(visible[0]?.item.start ?? 0);
    const paddingBottom = $derived(virtualizer.totalSize - (visible.at(-1)?.item.end ?? 0));

    // Deliberately reads no status: `loadMore` writes one, and an effect that reacts to its own
    // write would run again for every step the request takes. What it reads is what a scroll
    // changes, which is exactly when the next page is due. After a failure it is the scroll or
    // the retry button that asks again.
    $effect(() => {
        // Nothing is on screen before the first page, and it is that page which gives the list its
        // length -- so ask for the top of the mailbox and the rest follows from the scrolling.
        if (rowCount === 0) {
            store.ensureRange(0, 0);
            return;
        }

        const first = visible[0]?.item.index;
        const last = visible.at(-1)?.item.index;
        if (first === undefined || last === undefined) return;

        // Placeholders on screen mean the scroll has run past what is loaded. The store works out
        // which end to read from, and this fires again as each page lands until the range is
        // covered -- one request for a jump to the bottom, a walk for a jump into the middle.
        store.ensureRange(first - PREFETCH_ROWS, last + PREFETCH_ROWS);
    });
</script>

<section class="flex flex-col gap-3">
    <div class="flex items-baseline justify-between gap-4">
        <h2 class="text-sm font-medium">Alle Mails</h2>
        <p class="text-muted-foreground text-xs tabular-nums">
            {#if store.total > rows.length}
                {rows.length} von {store.total} Mails geladen
            {:else}
                {rows.length} {rows.length === 1 ? 'Mail' : 'Mails'}
            {/if}
        </p>
    </div>

    <!-- The scroll container has to be this one and not the table's own wrapper: the sticky
         header sticks to its nearest scrolling ancestor, and the wrapper never scrolls. -->
    <div
            bind:this={viewport}
            class="h-[70vh] overflow-auto rounded-md border"
    >
        <Table.Root class="min-w-5xl table-fixed">
            <Table.Header class="sticky top-0 z-10">
                {#each table.getHeaderGroups() as headerGroup (headerGroup.id)}
                    <Table.Row class="hover:bg-transparent">
                        {#each headerGroup.headers as header (header.id)}
                            <!-- The background is on the cells, not the row: rows scroll under a
                                 sticky `thead` and a transparent one lets them show through. The
                                 inset shadow stands in for a border, which a sticky element drops
                                 while it is stuck. -->
                            <Table.Head
                                    class={cn(
                                        'bg-background shadow-[inset_0_-1px_0_0_var(--border)]',
                                        COLUMN_WIDTHS[header.column.id]
                                    )}
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
                <!-- The spacers carry a cell of their own: a `<tr>` without one has no height to
                     give, whatever is set on it. -->
                {#if paddingTop > 0}
                    <tr aria-hidden="true" class="border-0">
                        <td colspan={columns.length} class="p-0" style="height: {paddingTop}px"></td>
                    </tr>
                {/if}

                {#each visible as { item, row } (row?.id ?? `pending-${item.index}`)}
                    {#if row}
                        <Table.Row class="h-12" data-index={item.index}>
                            {#each row.getAllCells() as cell (cell.id)}
                                <Table.Cell class="overflow-hidden">
                                    <FlexRender {cell} />
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {:else}
                        <!-- A mail the mailbox has and this page does not, yet. Same height as a
                             real row, so nothing shifts when it arrives. -->
                        <Table.Row class="h-12 hover:bg-transparent" aria-hidden="true">
                            {#each tableColumns as column (column.id)}
                                <Table.Cell class="overflow-hidden">
                                    <Skeleton class="h-4 w-2/3" />
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {/if}
                {/each}

                {#if paddingBottom > 0}
                    <tr aria-hidden="true" class="border-0">
                        <td colspan={columns.length} class="p-0" style="height: {paddingBottom}px"></td>
                    </tr>
                {/if}

                {#if rowCount === 0}
                    <Table.Row class="hover:bg-transparent">
                        <Table.Cell colspan={columns.length} class="text-muted-foreground h-24 text-center">
                            <!-- Not just an empty list: on the server nothing has been asked for
                                 yet, and "Keine Mails." would be the first thing the page paints
                                 for a mailbox that has thousands. -->
                            {store.initialized ? 'Keine Mails.' : 'Mails werden geladen …'}
                        </Table.Cell>
                    </Table.Row>
                {/if}
            </Table.Body>
        </Table.Root>
    </div>

    {#if store.status === 'error'}
        <div class="flex items-center gap-3">
            <p class="text-destructive text-xs">Weitere Mails konnten nicht geladen werden.</p>
            <Button variant="outline" size="sm" onclick={() => store.retry()}>Erneut versuchen</Button>
        </div>
    {/if}
</section>
