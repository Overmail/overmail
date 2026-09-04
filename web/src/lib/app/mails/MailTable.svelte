<!--
    Every mail in the mailbox, newest first, as a windowed table.

    Two sources, the same split as the stack: the list view model says which mail sits at which
    row, and the email repository says what each of them is and keeps it current. Only the rows
    near the viewport are in the DOM, and only those are subscribed.
-->
<script lang="ts">
    import {FlexRender, createTable} from "@tanstack/svelte-table";
    import {_} from "svelte-i18n";
    import * as Table from "$lib/components/ui/table";
    import {Button} from "$lib/components/ui/button";
    import {createVirtualizer} from "$lib/hooks/virtualizer.svelte";
    import {cn} from "$lib/utils";
    import {useRepositories} from "$lib/repository/repositories";
    import {COLUMN_WIDTHS, GHOST_SHAPES, columns, features, type MailTableRow} from "./columns";
    import {MailListViewModel} from "./MailListViewModel.svelte";
    import MailGhostCell from "./table/MailGhostCell.svelte";
    import MailsEmpty from "./MailsEmpty.svelte";

    /**
     * Rows cap at 32px -- the cell height is what a table row follows, see the classes below. Not
     * an estimate but the height: every row is one clipped line, and the scrollbar is sized from
     * this number.
     */
    const ROW_HEIGHT = 32;

    /** How many rows around the viewport are held and kept up to date. */
    const OVERSCAN = 12;

    /**
     * Stand-ins for the length nobody knows yet. Before the first page there would otherwise be
     * no rows at all and the empty state would flash.
     */
    const PLACEHOLDER_ROWS = 8;

    const {mails} = useRepositories();
    const list = new MailListViewModel(mails);

    let viewport = $state<HTMLDivElement | null>(null);

    /** Whether the length is known at all yet. Before that a handful of rows stand in for it. */
    const visibleRowCount = $derived(
        list.total === 0 && !list.initialized ? PLACEHOLDER_ROWS : list.total
    );

    const virtualizer = createVirtualizer<HTMLDivElement, HTMLTableRowElement>(() => {
        // Read out here rather than only inside `getScrollElement`: that closure is called by the
        // virtualizer, long after the effect that tracks these options has run, so reading it
        // there would not make the binding of the element reach the virtualizer.
        const scrollElement = viewport;

        return {
            count: visibleRowCount,
            getScrollElement: () => scrollElement,
            estimateSize: () => ROW_HEIGHT,
            overscan: OVERSCAN,
        };
    });

    /** The indexes on screen, with the mail each of them holds once it is known. */
    const visible = $derived(
        virtualizer.items
            .filter((item) => item.index < visibleRowCount)
            .map((item) => {
                const id = list.idAt(item.index);
                const mail = id === undefined ? null : mails.peek(id).value;
                return {item, row: id !== null && mail !== null ? {id: id!, mail} : undefined};
            })
    );

    /**
     * The rows the table has data for. Only those: a mail the list has not fetched yet is a gap
     * the virtualizer keeps open, not a row with nothing in it.
     */
    const dataRows = $derived<MailTableRow[]>(
        visible.map(({row}) => row).filter((row): row is MailTableRow => row !== undefined)
    );

    const table = createTable({
        features,
        columns,
        get data() {
            return dataRows;
        },
        getRowId: (row) => row.id,
    });

    // Looked up by id rather than by position: what the table holds is the window, and an index
    // in it says nothing about the row's place in the mailbox.
    const rowsById = $derived(new Map(table.getRowModel().rows.map((row) => [row.id, row])));

    // Asking is a write, so it happens in an effect: pages that are missing are fetched and the
    // rows on screen are subscribed. Fires again as answers land until the range is covered.
    $effect(() => {
        const first = visible[0]?.item.index;
        const last = visible.at(-1)?.item.index;

        if (first === undefined || last === undefined) {
            list.window(0, 0);
            return;
        }

        list.window(first, last);
    });

    $effect(() => () => list.dispose());

    // Spacer rows rather than absolutely positioned ones: a `<tr>` taken out of the flow loses
    // the table layout, and with it the column widths the sticky header lines up with.
    const paddingTop = $derived(visible[0]?.item.start ?? 0);
    const paddingBottom = $derived(virtualizer.totalSize - (visible.at(-1)?.item.end ?? 0));
</script>

<section class="flex flex-col gap-3">
    <div class="flex items-baseline gap-2 px-4">
        <h2 class="font-heading text-sm font-medium">{$_("mails.title")}</h2>
        {#if list.initialized}
            <span class="text-muted-foreground text-xs tabular-nums">
                {$_("mails.count", {values: {count: list.total}})}
            </span>
        {/if}
    </div>

    <!-- The scroll container, and the element the virtualizer measures. Its own box rather than
         the table's wrapper, which never scrolls.

         No frame and nothing rounded: the list is rows and nothing else, and a rounded box that
         scrolls clips the first and the last of them at the corners. -->
    <div
            bind:this={viewport}
            class="h-[70vh] overflow-auto [&>[data-slot=table-container]]:overflow-visible"
    >
        <!-- The row count is stated for a screen reader, because the DOM no longer carries it: a
             windowed table holds the rows near the viewport and nothing else. -->
        <Table.Root class="min-w-[52rem] table-fixed" aria-rowcount={list.total}>
            <colgroup>
                {#each columns as column (column.id)}
                    {@const width = COLUMN_WIDTHS[column.id ?? ""]}
                    <col style={width ? `width: ${width}` : undefined}/>
                {/each}
            </colgroup>

            <Table.Body>
                <!-- The rows that are not rendered, as height. A bare row rather than a
                     Table.Row: it carries no border, no hover and nothing to read, it is the
                     space the list would have taken. A `<tr>` without a cell has no height. -->
                {#if paddingTop > 0}
                    <tr aria-hidden="true">
                        <td colspan={columns.length} class="p-0" style="height: {paddingTop}px"></td>
                    </tr>
                {/if}

                {#each visible as {item, row} (row?.id ?? `pending-${item.index}`)}
                    {@const modelRow = row === undefined ? undefined : rowsById.get(row.id)}
                    {#if modelRow}
                        <!-- No line between rows: what separates them is the row height and the
                             hover, which is enough for one clipped line per mail. -->
                        <Table.Row aria-rowindex={item.index + 1} class={cn("h-8 border-0")}>
                            {#each modelRow.getAllCells() as cell (cell.id)}
                                <Table.Cell class="h-8 overflow-hidden py-0">
                                    <FlexRender {cell}/>
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {:else}
                        <!-- A mail the list has and does not hold yet: the shape of the row
                             without the content, so nothing shifts when it arrives. -->
                        <Table.Row aria-rowindex={item.index + 1} class="h-8 border-0 hover:bg-transparent">
                            {#each columns as column (column.id)}
                                {@const ghost = GHOST_SHAPES[column.id ?? ""]}
                                <Table.Cell class="h-8 overflow-hidden py-0">
                                    {#if ghost}
                                        <MailGhostCell widthClass={ghost.width} withAvatar={ghost.withAvatar}/>
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

                {#if list.initialized && list.total === 0}
                    <Table.Row class="border-0 hover:bg-transparent">
                        <Table.Cell colspan={columns.length} class="h-24 text-center">
                            <MailsEmpty/>
                        </Table.Cell>
                    </Table.Row>
                {/if}
            </Table.Body>
        </Table.Root>
    </div>

    {#if list.failed}
        <div class="flex items-center gap-3">
            <p class="text-destructive text-xs">{$_("mails.loadFailed")}</p>
            <Button variant="outline" size="sm" onclick={() => list.retry()}>
                {$_("mails.retry")}
            </Button>
        </div>
    {/if}
</section>
