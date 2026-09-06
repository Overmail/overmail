<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import KnowledgeForm from "$lib/app/settings/knowledge/KnowledgeForm.svelte";
    import {KnowledgeFormViewModel} from "$lib/app/settings/knowledge/KnowledgeFormViewModel.svelte.ts";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    let {
        entry = $bindable(null),
        onSaved,
    }: {
        /** The entry to edit; null closes the dialog. */
        entry: KnowledgeEntry | null,
        /** The saved entry as the server answered it, so the list can put it in place of the row. */
        onSaved?: (entry: KnowledgeEntry) => void,
    } = $props();

    const {knowledge} = useRepositories();

    /**
     * The entry being edited, kept next to the form.
     *
     * The id, not the name, is what the write goes to: renaming an entry is an edit of this one
     * and not a second entry under the new name.
     */
    let editedId: string | null = null;

    /** The same form the add dialog drives; what differs is that this one writes to an id. */
    const viewModel = new KnowledgeFormViewModel((draft) => {
        if (!editedId) throw new Error("No entry is being edited");
        return knowledge.update(editedId, draft);
    });

    // Opening fills the form with the entry as it stands. Not on closing: the dialog animates out,
    // and emptying the fields under it would show that happening.
    $effect(() => {
        const target = entry;
        if (!target) return;

        editedId = target.id;
        viewModel.reset(target);
    });

    function close() {
        if (viewModel.saving) return;
        entry = null;
    }

    /** Saves and closes; a failure leaves the dialog standing with the reason on it. */
    async function save() {
        const saved = await viewModel.submit();
        if (!saved) return;

        entry = null;
        onSaved?.(saved);
    }
</script>

<Dialog.Root
        open={entry !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <!-- The same width as the add dialog: it is the same form. -->
    <Dialog.Content class="sm:max-w-xl">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.knowledge.edit.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.knowledge.edit.description")}</Dialog.Description>
        </Dialog.Header>

        <!-- A form, so Enter in the name field saves, like the footer button does. -->
        <form
                class="flex flex-col gap-4"
                onsubmit={(event) => {
                    event.preventDefault();
                    void save();
                }}
        >
            <KnowledgeForm
                    viewModel={viewModel}
                    savingText={$_("settings.knowledge.edit.saving")}
                    failedText={$_("settings.knowledge.edit.failed")}
            />

            <Dialog.Footer>
                <Button type="button" variant="secondary" disabled={viewModel.saving} onclick={close}>
                    {$_("settings.knowledge.form.cancel")}
                </Button>
                <!-- Off until something is different: a save that writes the entry back unchanged
                     would only move the row to the top of the list. -->
                <Button type="submit" disabled={!viewModel.canSubmit}>
                    {$_("settings.knowledge.edit.submit")}
                </Button>
            </Dialog.Footer>
        </form>
    </Dialog.Content>
</Dialog.Root>
