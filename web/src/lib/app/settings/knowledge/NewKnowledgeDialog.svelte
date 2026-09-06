<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {Textarea} from "$lib/components/ui/textarea";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {WarningCircleIcon} from "phosphor-svelte";
    import KeywordsInput from "$lib/app/settings/knowledge/KeywordsInput.svelte";
    import {NewKnowledgeViewModel} from "$lib/app/settings/knowledge/NewKnowledgeViewModel.svelte.ts";
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
    const viewModel = new NewKnowledgeViewModel(knowledge);

    const id = $props.id();

    // The dialog is kept mounted, so without this the next entry would open inside the one that
    // was just written -- or inside a half-filled form somebody walked away from.
    $effect(() => {
        if (!open) viewModel.reset();
    });

    const saveState = $derived(viewModel.saveState);

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
            <Field.Field>
                <Field.Label for={"knowledge-name-" + id}>
                    {$_("settings.knowledge.new.name")}
                </Field.Label>
                <Input
                        id={"knowledge-name-" + id}
                        type="text"
                        disabled={viewModel.saving}
                        aria-invalid={saveState.type === "nameTaken"}
                        placeholder={$_("settings.knowledge.new.namePlaceholder")}
                        bind:value={() => viewModel.name, (name: string) => viewModel.setName(name)}
                />
                <!--
                  Under the field it is about: the name is the one thing the server can refuse for
                  a reason the user can act on, and it is a rename, not a retry.
                -->
                {#if saveState.type === "nameTaken"}
                    <Field.Error>{$_("settings.knowledge.new.nameTaken")}</Field.Error>
                {/if}
            </Field.Field>

            <Field.Field>
                <Field.Label for={"knowledge-description-" + id}>
                    {$_("settings.knowledge.new.text")}
                </Field.Label>
                <Textarea
                        id={"knowledge-description-" + id}
                        rows={4}
                        disabled={viewModel.saving}
                        placeholder={$_("settings.knowledge.new.textPlaceholder")}
                        bind:value={viewModel.description}
                />
                <Field.Description>{$_("settings.knowledge.new.textHint")}</Field.Description>
            </Field.Field>

            <Field.Field>
                <Field.Label for={"knowledge-keywords-" + id}>
                    {$_("settings.knowledge.new.keywords")}
                </Field.Label>
                <KeywordsInput
                        id={"knowledge-keywords-" + id}
                        keywords={viewModel.keywords}
                        bind:draft={viewModel.keywordDraft}
                        full={viewModel.keywordsFull}
                        disabled={viewModel.saving}
                        placeholder={$_("settings.knowledge.new.keywordsPlaceholder")}
                        oncommit={() => viewModel.commitKeywords()}
                        onremove={(keyword) => viewModel.removeKeyword(keyword)}
                        oneditlast={() => viewModel.editLastKeyword()}
                />
                <!--
                  Worth saying out loud: these are what the assistant looks the entry up by while
                  it sorts a mail, so an entry nobody gave words to is one it will rarely find.
                -->
                <Field.Description>{$_("settings.knowledge.new.keywordsHint")}</Field.Description>
            </Field.Field>

            <Field.Field>
                <Field.Label for={"knowledge-relevant-on-" + id}>
                    {$_("settings.knowledge.new.relevantOn")}
                </Field.Label>
                <Input
                        id={"knowledge-relevant-on-" + id}
                        type="date"
                        class="w-fit"
                        disabled={viewModel.saving}
                        bind:value={viewModel.relevantOn}
                />
                <Field.Description>{$_("settings.knowledge.new.relevantOnHint")}</Field.Description>
            </Field.Field>

            <div aria-live="polite" class="min-h-5 text-sm">
                {#if saveState.type === "saving"}
                    <div class="text-muted-foreground flex flex-row items-start gap-2">
                        <Spinner class="mt-0.5 size-4 shrink-0" />
                        <span>{$_("settings.knowledge.new.saving")}</span>
                    </div>
                {:else if saveState.type === "failed"}
                    <div class="text-destructive flex flex-row items-start gap-2">
                        <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                        <span>{$_("settings.knowledge.new.failed")}</span>
                    </div>
                {/if}
            </div>

            <Dialog.Footer>
                <Button type="button" variant="secondary" disabled={viewModel.saving} onclick={() => (open = false)}>
                    {$_("settings.knowledge.new.cancel")}
                </Button>
                <Button type="submit" disabled={!viewModel.canSubmit}>
                    {$_("settings.knowledge.new.submit")}
                </Button>
            </Dialog.Footer>
        </form>
    </Dialog.Content>
</Dialog.Root>
