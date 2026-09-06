<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import KnowledgeForm from "$lib/app/settings/knowledge/KnowledgeForm.svelte";
    import {KnowledgeFormViewModel} from "$lib/app/settings/knowledge/KnowledgeFormViewModel.svelte.ts";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(false),
        onCreated,
    }: {
        open: boolean,
        /** Called once the entry exists, so whoever lists them can re-read. */
        onCreated?: () => void,
    } = $props();

    const {knowledge} = useRepositories();
    const viewModel = new KnowledgeFormViewModel((draft) => knowledge.create(draft));

    // The dialog is kept mounted, so without this the next entry would open inside the one that
    // was just written -- or inside a half-filled form somebody walked away from.
    $effect(() => {
        if (!open) viewModel.reset();
    });

    /** Writes the entry and closes on success; a failure leaves the form standing. */
    async function save() {
        const entry = await viewModel.submit();
        if (!entry) return;

        open = false;
        onCreated?.();
    }
</script>

<Dialog.Root bind:open>
    <Dialog.Content class="sm:max-w-xl">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.knowledge.new.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.knowledge.new.description")}</Dialog.Description>
        </Dialog.Header>

        <!-- A form, so Enter in the name field writes the entry, like the footer button does. -->
        <form
                class="flex flex-col gap-4"
                onsubmit={(event) => {
                    event.preventDefault();
                    void save();
                }}
        >
            <KnowledgeForm
                    viewModel={viewModel}
                    savingText={$_("settings.knowledge.new.saving")}
                    failedText={$_("settings.knowledge.new.failed")}
            />

            <Dialog.Footer>
                <Button type="button" variant="secondary" disabled={viewModel.saving} onclick={() => (open = false)}>
                    {$_("settings.knowledge.form.cancel")}
                </Button>
                <Button type="submit" disabled={!viewModel.canSubmit}>
                    {$_("settings.knowledge.new.submit")}
                </Button>
            </Dialog.Footer>
        </form>
    </Dialog.Content>
</Dialog.Root>
