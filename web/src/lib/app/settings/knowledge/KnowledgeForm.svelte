<script lang="ts">
    import * as Field from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import {Textarea} from "$lib/components/ui/textarea";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {WarningCircleIcon} from "phosphor-svelte";
    import KeywordsInput from "$lib/app/settings/knowledge/KeywordsInput.svelte";
    import type {KnowledgeFormViewModel} from "$lib/app/settings/knowledge/KnowledgeFormViewModel.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        viewModel,
        savingText,
        failedText,
    }: {
        viewModel: KnowledgeFormViewModel,
        /** What "it is being written" reads as here -- adding and saving are not the same sentence. */
        savingText: string,
        failedText: string,
    } = $props();

    const id = $props.id();

    const saveState = $derived(viewModel.saveState);
</script>

<Field.Field>
    <Field.Label for={"knowledge-name-" + id}>
        {$_("settings.knowledge.form.name")}
    </Field.Label>
    <Input
            id={"knowledge-name-" + id}
            type="text"
            disabled={viewModel.saving}
            aria-invalid={saveState.type === "nameTaken"}
            placeholder={$_("settings.knowledge.form.namePlaceholder")}
            bind:value={() => viewModel.name, (name: string) => viewModel.setName(name)}
    />
    <!--
      Under the field it is about: the name is the one thing the server can refuse for
      a reason the user can act on, and it is a rename, not a retry.
    -->
    {#if saveState.type === "nameTaken"}
        <Field.Error>{$_("settings.knowledge.form.nameTaken")}</Field.Error>
    {/if}
</Field.Field>

<Field.Field>
    <Field.Label for={"knowledge-description-" + id}>
        {$_("settings.knowledge.form.text")}
    </Field.Label>
    <Textarea
            id={"knowledge-description-" + id}
            rows={4}
            disabled={viewModel.saving}
            placeholder={$_("settings.knowledge.form.textPlaceholder")}
            bind:value={viewModel.description}
    />
    <Field.Description>{$_("settings.knowledge.form.textHint")}</Field.Description>
</Field.Field>

<Field.Field>
    <Field.Label for={"knowledge-keywords-" + id}>
        {$_("settings.knowledge.form.keywords")}
    </Field.Label>
    <KeywordsInput
            id={"knowledge-keywords-" + id}
            keywords={viewModel.keywords}
            bind:draft={viewModel.keywordDraft}
            full={viewModel.keywordsFull}
            disabled={viewModel.saving}
            placeholder={$_("settings.knowledge.form.keywordsPlaceholder")}
            oncommit={() => viewModel.commitKeywords()}
            onremove={(keyword) => viewModel.removeKeyword(keyword)}
            oneditlast={() => viewModel.editLastKeyword()}
    />
    <!--
      Worth saying out loud: these are what the assistant looks the entry up by while
      it sorts a mail, so an entry nobody gave words to is one it will rarely find.
    -->
    <Field.Description>{$_("settings.knowledge.form.keywordsHint")}</Field.Description>
</Field.Field>

<Field.Field>
    <Field.Label for={"knowledge-relevant-on-" + id}>
        {$_("settings.knowledge.form.relevantOn")}
    </Field.Label>
    <Input
            id={"knowledge-relevant-on-" + id}
            type="date"
            class="w-fit"
            disabled={viewModel.saving}
            bind:value={viewModel.relevantOn}
    />
    <Field.Description>{$_("settings.knowledge.form.relevantOnHint")}</Field.Description>
</Field.Field>

<div aria-live="polite" class="min-h-5 text-sm">
    {#if saveState.type === "saving"}
        <div class="text-muted-foreground flex flex-row items-start gap-2">
            <Spinner class="mt-0.5 size-4 shrink-0" />
            <span>{savingText}</span>
        </div>
    {:else if saveState.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{failedText}</span>
        </div>
    {/if}
</div>
