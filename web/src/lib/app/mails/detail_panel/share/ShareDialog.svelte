<!--
    Where a mail is handed out: the form that makes a link, and the links that are already out.

    Both in one dialog rather than a screen of its own, because they are one thought -- somebody
    who opens this either wants a link or wants to take one back, and the list is what says which
    of the two they are looking at.
-->
<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import * as Select from "$lib/components/ui/select";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {Label} from "$lib/components/ui/label";
    import {Spinner} from "$lib/components/ui/spinner";
    import {LinkSimpleIcon, WarningCircleIcon} from "phosphor-svelte";
    import {onMount} from "svelte";
    import {slide} from "svelte/transition";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";
    import {
        MIN_PASSWORD_LENGTH,
        SHARE_EXPIRIES,
        ShareDialogViewModel,
    } from "$lib/app/mails/detail_panel/share/ShareDialogViewModel.svelte";
    import ShareList from "$lib/app/mails/detail_panel/share/ShareList.svelte";
    import DeleteShareDialog from "$lib/app/mails/detail_panel/share/DeleteShareDialog.svelte";
    import {shareUrl, subjectFor} from "$lib/app/mails/detail_panel/share/sharePath";

    let {
        open = $bindable(true),
        emailId,
        subject,
    }: {
        open?: boolean,
        /** The mail the links are for. A dialog is opened on one mail and lives as long as it. */
        emailId: string,
        /** What the link says it is about, where the share lets a visitor see that anyway. */
        subject?: string | null,
    } = $props();

    let id = $props.id();

    const {shares} = useRepositories();
    // The dialog is mounted per opening and lives as long as the mail it was opened on, so the
    // id it captures here is the id it has for its whole life.
    // svelte-ignore state_referenced_locally
    const viewModel = new ShareDialogViewModel(emailId, shares);

    /** Says the clipboard refused, which is a thing a browser does without asking the user. */
    let copyFailed = $state(false);

    onMount(() => {
        const abort = new AbortController();
        void viewModel.load(abort.signal);
        return () => abort.abort();
    });

    /** What the message under the form says, or null while nothing is wrong. */
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

    /**
     * Writes the link and, for a new one, puts it on the clipboard straight away.
     *
     * That is what the button says it does: a link nobody copied is a link nobody has, and the
     * list below keeps a copy button for the ones made earlier.
     */
    async function save() {
        const creating = !viewModel.isEditing;
        const share = await viewModel.submit();
        if (!share) return;

        if (creating) {
            const url = shareUrl(share.id, location.origin, subjectFor(share, subject));
            copyFailed = !(await viewModel.copy(share, url));
        }
    }
</script>

<Dialog.Root bind:open>
    <Dialog.Content class="sm:max-w-xl">
        <Dialog.Header>
            <Dialog.Title>{$_("mails.share.title")}</Dialog.Title>
            <Dialog.Description>{$_("mails.share.description")}</Dialog.Description>
        </Dialog.Header>

        <!-- A form, so Enter in the name field makes the link, like the button does. -->
        <form
                class="flex flex-col gap-2"
                onsubmit={(event) => {
                    event.preventDefault();
                    void save();
                }}
        >
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
                        <!-- The field is empty on an edit as well, and there it means something
                             else than it does on a new link. -->
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
                {:else if copyFailed}
                    <span class="text-muted-foreground">{$_("mails.share.list.copyFailed")}</span>
                {/if}
            </div>

            <div class="flex flex-row gap-2 self-end">
                {#if viewModel.isEditing}
                    <!-- Back to a new link, which is what the form was on before the edit. -->
                    <Button type="button" variant="secondary" onclick={() => viewModel.startCreate()}>
                        {$_("mails.share.form.cancel")}
                    </Button>
                {/if}

                <Button type="submit" disabled={!viewModel.canSubmit}>
                    {#if !viewModel.isEditing}
                        <LinkSimpleIcon/>
                    {/if}
                    {viewModel.isEditing ? $_("mails.share.form.save") : $_("mails.share.form.create")}
                </Button>
            </div>
        </form>

        <div class="h-px w-full bg-muted my-3"></div>

        <ShareList {viewModel} {subject} onCopyFailed={(failed) => (copyFailed = failed)} />
    </Dialog.Content>
</Dialog.Root>

<DeleteShareDialog {viewModel} />
