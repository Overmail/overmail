<script lang="ts">
    import * as Table from "$lib/components/ui/table";
    import KnowledgeRow from "$lib/app/settings/knowledge/KnowledgeRow.svelte";
    import {PINNED_LEFT_EDGE, PINNED_LEFT_EDGE_ON} from "$lib/app/settings/knowledge/pinnedColumn";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
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

    /**
     * The box that scrolls, in both directions.
     *
     * Its own rather than `Table.Root`'s: that wrapper carries fixed classes and no height, and
     * this table needs a bounded one -- the rows scroll inside the dialog instead of pushing it,
     * which is also what gives the header something to stick to.
     */
    let scroller: HTMLDivElement | null = $state(null);
    let tableElement: HTMLTableElement | null = $state(null);
    /** Whether anything is hidden past the left edge, which is what the name column marks. */
    let scrolledFromStart = $state(false);

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
</script>

<div class="min-h-0 flex-1 overflow-hidden rounded-2xl border">
    <div bind:this={scroller} class="h-full overflow-auto">
        <table bind:this={tableElement} class="w-full caption-bottom text-sm">
            <Table.Header>
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
                {#each rows as entry (entry.id)}
                    <KnowledgeRow {entry} pinned={scrolledFromStart} {onedit} {ondelete} {onchanged} />
                {/each}
            </Table.Body>
        </table>
    </div>
</div>
