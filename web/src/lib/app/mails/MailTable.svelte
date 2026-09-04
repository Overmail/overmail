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
    import {createWindowVirtualizer} from "$lib/hooks/virtualizer.svelte";
    import {cn} from "$lib/utils";
    import {useRepositories} from "$lib/repository/repositories";
    import {COLUMN_WIDTHS, GHOST_SHAPES, columns, features, type MailTableRow} from "./columns";
    import {MailListViewModel} from "./MailListViewModel.svelte";
    import MailGhostCell from "./table/MailGhostCell.svelte";
    import MailGroupHeader from "./table/MailGroupHeader.svelte";
    import MailsEmpty from "./MailsEmpty.svelte";

    /**
     * Not estimates but the heights: every row is one clipped line, so the scrollbar is sized
     * from these two numbers rather than from anything measured.
     *
     * Which is also the catch -- a row that renders taller than its number here drifts the whole
     * list. [HEADER_HEIGHT] is the box MailGroupHeader takes: its own line plus the space it sets
     * above and below itself, so the two have to be changed together.
     */
    const ROW_HEIGHT = 40;
    const HEADER_HEIGHT = 60;

    /** How many rows around the viewport are held and kept up to date. */
    const OVERSCAN = 12;

    /**
     * Stand-ins for the length nobody knows yet. Before the first page there would otherwise be
     * no rows at all and the empty state would flash.
     */
    const PLACEHOLDER_ROWS = 8;

    const {mails} = useRepositories();
    const list = new MailListViewModel(mails);

    /** The box the rows sit in, measured to know where the list starts on the page. */
    let listElement = $state<HTMLDivElement | null>(null);

    /**
     * How far down the page the first row sits. The page is the scroll container, so this is
     * what tells the virtualizer which of its rows the scroll position means -- and it is why
     * the greeting and the heatmap above simply scroll away before the rows start moving.
     */
    let scrollMargin = $state(0);

    $effect(() => {
        const element = listElement;
        if (element === null) return;

        const measure = () => (scrollMargin = element.getBoundingClientRect().top + window.scrollY);
        measure();

        // Everything above the list can change height -- the heatmap filling in, the greeting
        // arriving, the window being resized -- and each of those moves where the list starts.
        const observer = new ResizeObserver(measure);
        observer.observe(document.body);
        return () => observer.disconnect();
    });

    /** Whether the length is known at all yet. Before that a handful of rows stand in for it. */
    const visibleRowCount = $derived(
        list.layout.length === 0 && !list.initialized ? PLACEHOLDER_ROWS : list.layout.length
    );

    const virtualizer = createWindowVirtualizer<HTMLTableRowElement>(() => {
        // Read out here rather than inside the callbacks below: those are called by the
        // virtualizer, long after the effect that tracks these options has run.
        const layout = list.layout;

        return {
            count: visibleRowCount,
            // Follows the layout: a stretch of headers appearing changes which rows are tall.
            estimateSize: (index: number) =>
                layout.rowAt(index)?.kind === "header" ? HEADER_HEIGHT : ROW_HEIGHT,
            overscan: OVERSCAN,
            scrollMargin,
        };
    });

    /**
     * The rows on screen: what the layout says each of them is, and the mail it holds once that
     * is known. A `row` of undefined is a mail whose page is not here yet.
     */
    const visible = $derived(
        virtualizer.items
            .filter((item) => item.index < visibleRowCount)
            .map((item) => {
                const entry = list.layout.rowAt(item.index);
                if (entry?.kind === "header") return {item, entry, row: undefined};

                const id = entry === undefined ? undefined : list.idAt(entry.index);
                const mail = id === undefined ? null : mails.peek(id).value;
                return {
                    item,
                    entry,
                    row: id !== undefined && mail !== null ? {id, mail} : undefined,
                };
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
    // the table layout, and with it the column widths.
    //
    // Item positions are page positions, so the margin comes back off them -- what is padded
    // here is the space inside the list, not the space in front of it.
    const paddingTop = $derived((visible[0]?.item.start ?? scrollMargin) - scrollMargin);
    const paddingBottom = $derived(
        virtualizer.totalSize - ((visible.at(-1)?.item.end ?? scrollMargin) - scrollMargin)
    );
</script>

<section class="flex flex-col">
    <!-- Stays under the app header while the rows run past it: this is the bar the filters go
         into, and a filter that scrolls out of reach is one nobody uses. `top-12` is that
         header's height, and the background is its own -- rows would show through it. -->
    <div class="bg-background sticky top-12 z-20 flex items-baseline gap-2 px-4 py-2">
        <h2 class="font-heading text-sm font-medium">{$_("mails.title")}</h2>
        {#if list.initialized}
            <span class="text-muted-foreground text-xs tabular-nums">
                {$_("mails.count", {values: {count: list.total}})}
            </span>
        {/if}
    </div>

    <!-- No scroll container of its own: the page is the one, so the greeting and the heatmap
         above scroll away before the rows begin to move. The table's own wrapper is kept from
         scrolling as well, or it would clip the rows vertically along with sideways. -->
    <div
            bind:this={listElement}
            class="[&>[data-slot=table-container]]:overflow-visible"
    >
        <!-- The row count is stated for a screen reader, because the DOM no longer carries it: a
             windowed table holds the rows near the viewport and nothing else. -->
        <!-- The body reads back rather than at full contrast: a mailbox is a long list of rows nobody
             reads one by one, so what has been read stays quiet and the unread rows below step
             forward against it. -->
        <Table.Root class="text-muted-foreground min-w-[52rem] table-fixed" aria-rowcount={list.total}>
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

                {#each visible as {item, entry, row} (row?.id ?? `row-${item.index}`)}
                    {@const modelRow = row === undefined ? undefined : rowsById.get(row.id)}
                    {#if entry?.kind === "header"}
                        <!-- The stretch this and the rows below it belong to. One cell across the
                             table: a heading is not a value in a column. -->
                        <Table.Row class="border-0 hover:bg-transparent">
                            <Table.Cell colspan={columns.length} class="p-0 align-bottom">
                                <MailGroupHeader label={entry.label} count={entry.count}/>
                            </Table.Cell>
                        </Table.Row>
                    {:else if modelRow}
                        <!-- No line between rows: what separates them is the row height and the
                             hover, which is enough for one clipped line per mail. -->
                        <Table.Row aria-rowindex={item.index + 1} class={cn("h-10 border-0")}>
                            {#each modelRow.getAllCells() as cell (cell.id)}
                                <Table.Cell class="h-10 overflow-hidden py-0">
                                    <FlexRender {cell}/>
                                </Table.Cell>
                            {/each}
                        </Table.Row>
                    {:else}
                        <!-- A mail the list has and does not hold yet: the shape of the row
                             without the content, so nothing shifts when it arrives. -->
                        <Table.Row aria-rowindex={item.index + 1} class="h-10 border-0 hover:bg-transparent">
                            {#each columns as column (column.id)}
                                {@const ghost = GHOST_SHAPES[column.id ?? ""]}
                                <Table.Cell class="h-10 overflow-hidden py-0">
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

                {#if list.initialized && list.layout.length === 0}
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
