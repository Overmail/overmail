<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import * as Empty from "$lib/components/ui/empty";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {BrainIcon, MagnifyingGlassIcon, PlusIcon, WarningCircleIcon} from "phosphor-svelte";
    import KnowledgeTable from "$lib/app/settings/knowledge/KnowledgeTable.svelte";
    import KnowledgeSearchField from "$lib/app/settings/knowledge/KnowledgeSearchField.svelte";
    import NewKnowledgeDialog from "$lib/app/settings/knowledge/NewKnowledgeDialog.svelte";
    import EditKnowledgeDialog from "$lib/app/settings/knowledge/EditKnowledgeDialog.svelte";
    import DeleteKnowledgeDialog from "$lib/app/settings/knowledge/DeleteKnowledgeDialog.svelte";
    import {searchKnowledge} from "$lib/app/settings/knowledge/knowledgeSearch";
    import {useRepositories} from "$lib/repository/repositories";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {_} from "svelte-i18n";

    const {knowledge: knowledgeRepository} = useRepositories();

    let showNewKnowledgeDialog = $state(false);
    /** What is typed in the search field; the table shows what accounts for it. */
    let query = $state("");
    /** The entry the edit dialog is open on; null while it is closed. */
    let entryToEdit: KnowledgeEntry | null = $state(null);
    /** The entry the delete dialog is asking about; null while it is closed. */
    let entryToDelete: KnowledgeEntry | null = $state(null);

    let entries: {type: "loading"} | {type: "loaded"; rows: KnowledgeEntry[]} | {type: "failed"} = $state({
        type: "loading",
    });

    async function load() {
        entries = {type: "loading"};
        try {
            entries = {type: "loaded", rows: await knowledgeRepository.list()};
        } catch {
            // Not an empty state, same as the mailboxes: "nothing could be read" and "the
            // assistant has not learned anything yet" are different answers.
            entries = {type: "failed"};
        }
    }

    /**
     * An entry a row saved by itself, as the server answered it.
     *
     * The row is patched rather than the list re-read, like the mailbox table does with pausing:
     * re-reading would put the whole table through its loading state for one changed cell, and
     * would move rows under the pointer -- the list is sorted by when an entry was last written.
     */
    function patchRow(entry: KnowledgeEntry) {
        if (entries.type !== "loaded") return;
        entries = {
            type: "loaded",
            rows: entries.rows.map((row) => (row.id === entry.id ? entry : row)),
        };
    }

    /**
     * The rows the table shows: everything that was loaded, narrowed by the search.
     *
     * Filtered here rather than re-read from the server: the whole list is already in hand, and a
     * request per keystroke would answer slower than typing and no more accurately.
     */
    // `$derived.by`, not `$derived`: read straight after the declaration, `entries` is still
    // narrowed to the type it was initialised with, and the comparison looks impossible.
    const visibleRows = $derived.by(() =>
        entries.type === "loaded" ? searchKnowledge(entries.rows, query) : [],
    );

    $effect(() => {
        void load();
    });
</script>

<!-- `min-h-0` so the table below can bound its own height and scroll inside the dialog. -->
<div class="flex min-h-0 min-w-0 flex-1 flex-col grow">
    {#if entries.type === "loading"}
        <div class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
            <Spinner class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.knowledge.list.loading")}</span>
        </div>
    {:else if entries.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2 text-sm">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.knowledge.list.failed")}</span>
        </div>
    {:else if entries.rows.length > 0}
        <div class="flex min-h-0 flex-1 flex-col gap-4">
            <div class="flex flex-row items-center gap-2">
                <Button class="w-fit" onclick={() => (showNewKnowledgeDialog = true)}>
                    <PlusIcon />
                    {$_("settings.knowledge.add")}
                </Button>
                <KnowledgeSearchField bind:value={query} class="max-w-xs flex-1" />
            </div>

            {#if visibleRows.length > 0}
                <KnowledgeTable
                        rows={visibleRows}
                        onedit={(entry) => (entryToEdit = entry)}
                        ondelete={(entry) => (entryToDelete = entry)}
                        onchanged={patchRow}
                />
            {:else}
                <!--
                  Only reachable while something is typed: with an empty query the search hands
                  back the list it was given, and a list with nothing in it took the empty state
                  below instead.
                -->
                <Empty.Root class="flex flex-col items-center justify-center border p-8">
                    <Empty.Header>
                        <Empty.Media variant="icon">
                            <MagnifyingGlassIcon />
                        </Empty.Media>
                        <Empty.Title>{$_("settings.knowledge.search.noResults.title")}</Empty.Title>
                        <Empty.Description>
                            {$_("settings.knowledge.search.noResults.description", {values: {query}})}
                        </Empty.Description>
                    </Empty.Header>
                </Empty.Root>
            {/if}
        </div>
    {:else}
        <Empty.Root class="h-full flex flex-col items-center justify-center border">
            <Empty.Header>
                <Empty.Media variant="icon">
                    <BrainIcon />
                </Empty.Media>
                <Empty.Title>{$_("settings.knowledge.empty.title")}</Empty.Title>
                <Empty.Description>{$_("settings.knowledge.empty.description")}</Empty.Description>
            </Empty.Header>

            <Empty.Content>
                <div class="flex flex-row items-center gap-2">
                    <Button class="w-fit" onclick={() => (showNewKnowledgeDialog = true)}>
                        <PlusIcon />
                        {$_("settings.knowledge.add")}
                    </Button>
                </div>
            </Empty.Content>
        </Empty.Root>
    {/if}
</div>

<!-- Writing an entry can put it anywhere in the order, so this one re-reads rather than patches. -->
<NewKnowledgeDialog bind:open={showNewKnowledgeDialog} onCreated={load} />

<!-- The saved entry comes back from the dialog, so the row is patched instead of the list re-read. -->
<EditKnowledgeDialog bind:entry={entryToEdit} onSaved={patchRow} />

<DeleteKnowledgeDialog bind:entry={entryToDelete} onDeleted={() => void load()} />
