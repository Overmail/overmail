<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import * as Empty from "$lib/components/ui/empty";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {BrainIcon, PlusIcon, WarningCircleIcon} from "phosphor-svelte";
    import KnowledgeTable from "$lib/app/settings/knowledge/KnowledgeTable.svelte";
    import NewKnowledgeDialog from "$lib/app/settings/knowledge/NewKnowledgeDialog.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {_} from "svelte-i18n";

    const {knowledge: knowledgeRepository} = useRepositories();

    let showNewKnowledgeDialog = $state(false);
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
            <Button class="w-fit" onclick={() => (showNewKnowledgeDialog = true)}>
                <PlusIcon />
                {$_("settings.knowledge.add")}
            </Button>

            <KnowledgeTable
                    rows={entries.rows}
                    onedit={(entry) => (entryToEdit = entry)}
                    ondelete={(entry) => (entryToDelete = entry)}
                    onchanged={patchRow}
            />
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

<!-- The dialog does not know about this list, so it says it wrote something and this re-reads. -->
<NewKnowledgeDialog bind:open={showNewKnowledgeDialog} onCreated={load} />
