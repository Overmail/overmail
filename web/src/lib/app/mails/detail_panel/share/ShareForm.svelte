<!--
    The fields of a share, in the create dialog and in the edit window alike.

    One component, two view models: each dialog brings its own, so the window that edits a link
    that is already out does not type into the form standing behind it.
-->
<script lang="ts">
    import * as Field from "$lib/components/ui/field";
    import * as Select from "$lib/components/ui/select";
    import {Input} from "$lib/components/ui/input";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {Label} from "$lib/components/ui/label";
    import {Spinner} from "$lib/components/ui/spinner";
    import {WarningCircleIcon} from "phosphor-svelte";
    import {slide} from "svelte/transition";
    import {_} from "svelte-i18n";
    import {
        MIN_PASSWORD_LENGTH,
        SHARE_EXPIRIES,
        type ShareFormViewModel,
    } from "$lib/app/mails/detail_panel/share/ShareFormViewModel.svelte";

    let {
        viewModel,
        note,
    }: {
        viewModel: ShareFormViewModel,
        /** What the dialog wants said under the fields when nothing is wrong -- e.g. that the link was copied. */
        note?: string | null,
    } = $props();

    let id = $props.id();

    /** What the message under the fields says, or null while nothing is wrong. */
    const problemText = $derived.by(() => {
        switch (viewModel.problem) {
            case "password-too-short":
                return $_("mails.share.form.passwordTooShort", {values: {count: MIN_PASSWORD_LENGTH}});
            case "expiry-missing":
                return $_("mails.share.form.expiryMissing");
            case "expiry-past":
                return $_("mails.share.form.expiryPast");
            default:
                return null;
        }
    });
</script>

<div class="flex flex-col gap-2">
    <Field.Field class="flex flex-col gap-1">
        <Field.Label for={"sharename-" + id}>{$_("mails.share.form.name")}</Field.Label>

        <Input
                type="text"
                id={"sharename-" + id}
                placeholder={$_("mails.share.form.namePlaceholder")}
                bind:value={viewModel.shareName}
        />

        <Field.Description>{$_("mails.share.form.nameHint")}</Field.Description>
    </Field.Field>

    <div class="flex flex-col">
        <Field.Field class="flex flex-col gap-1">
            <Field.Label for={"password-" + id}>{$_("mails.share.form.password")}</Field.Label>

            <Input
                    type="password"
                    id={"password-" + id}
                    bind:value={viewModel.password}
                    placeholder={$_("mails.share.form.passwordPlaceholder")}
            />

            <Field.Description>
                <!-- The field is empty on an edit as well, and there it means something else
                     than it does on a new link. -->
                {viewModel.replacingPassword
                    ? $_("mails.share.form.passwordKeepHint")
                    : $_("mails.share.form.passwordHint")}
            </Field.Description>
        </Field.Field>

        {#if viewModel.replacingPassword && viewModel.password.length === 0}
            <div class="flex items-center gap-3 my-4 ml-4" transition:slide={{axis: "y"}}>
                <Checkbox id={"remove-password-" + id} bind:checked={viewModel.removePassword}/>
                <Label for={"remove-password-" + id}>{$_("mails.share.form.removePassword")}</Label>
            </div>
        {/if}

        {#if viewModel.password || (viewModel.replacingPassword && !viewModel.removePassword)}
            <div class="flex items-center gap-3 my-4 ml-4" transition:slide={{axis: "y"}}>
                <Checkbox id={"metadata-" + id} bind:checked={viewModel.allowMetadataWithoutPassword}/>
                <Label for={"metadata-" + id}>{$_("mails.share.form.metadata")}</Label>
            </div>
        {/if}
    </div>

    <Field.Field class="flex flex-col gap-1">
        <Field.Label for={"expiry-" + id}>{$_("mails.share.form.expiry")}</Field.Label>

        <Select.Root type="single" bind:value={viewModel.expiry}>
            <Select.Trigger id={"expiry-" + id} class="w-full">
                {$_(`mails.share.form.expiryOptions.${viewModel.expiry}`)}
            </Select.Trigger>
            <Select.Content>
                <Select.Group>
                    {#each SHARE_EXPIRIES as expiry (expiry)}
                        <Select.Item value={expiry}>
                            {$_(`mails.share.form.expiryOptions.${expiry}`)}
                        </Select.Item>
                    {/each}
                </Select.Group>
            </Select.Content>
        </Select.Root>

        {#if viewModel.expiry === "custom"}
            <div transition:slide={{axis: "y"}}>
                <Input
                        type="date"
                        aria-label={$_("mails.share.form.expiresOn")}
                        bind:value={viewModel.expiresOn}
                />
            </div>
        {/if}
    </Field.Field>

    <Field.Field class="flex flex-col gap-1">
        <div class="flex items-center gap-3">
            <Checkbox id={"include-labels-" + id} bind:checked={viewModel.includeLabels}/>
            <Label for={"include-labels-" + id}>{$_("mails.share.form.labels")}</Label>
        </div>
    </Field.Field>

    <div aria-live="polite" class="min-h-5 text-sm">
        {#if viewModel.saving}
            <div class="text-muted-foreground flex flex-row items-start gap-2">
                <Spinner class="mt-0.5 size-4 shrink-0" />
                <span>{$_("mails.share.form.saving")}</span>
            </div>
        {:else if viewModel.saveState.type === "failed"}
            <div class="text-destructive flex flex-row items-start gap-2">
                <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                <span>{$_("mails.share.form.failed")}</span>
            </div>
        {:else if problemText}
            <span class="text-destructive">{problemText}</span>
        {:else if note}
            <span class="text-muted-foreground">{note}</span>
        {/if}
    </div>
</div>
