<!--
    What the mailbox is called: one optional field, shared by the setup dialog and the edit screen.

    Not a step of its own and checked against nothing -- a name is not something a mail server has
    an opinion on. The login stands in the empty field, because that is what the mailbox is shown
    as while it has no name.
-->
<script lang="ts">
    import * as Field from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import type {NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        viewModel,
        labelled = true,
    }: {
        viewModel: NewEmailAccountViewModel,
        /** Whether the field brings its label. Off where a heading above it already says so. */
        labelled?: boolean,
    } = $props();

    const id = $props.id();
</script>

<!-- As wide as a field of the steps before it, not as the folder table beneath. -->
<Field.Field class="max-w-sm">
    {#if labelled}
        <Field.Label for={"inbox-name-" + id}>{$_("settings.emailAccounts.name.label")}</Field.Label>
    {/if}
    <Input
            id={"inbox-name-" + id}
            type="text"
            autocomplete="off"
            maxlength={255}
            aria-label={labelled ? undefined : $_("settings.emailAccounts.name.label")}
            placeholder={viewModel.username.trim() || $_("settings.emailAccounts.name.placeholder")}
            bind:value={viewModel.name}
    />
    <Field.Description>{$_("settings.emailAccounts.name.hint")}</Field.Description>
</Field.Field>
