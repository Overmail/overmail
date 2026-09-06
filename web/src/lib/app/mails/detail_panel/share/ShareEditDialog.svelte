<!--
    Changing a link that is already out.

    A window of its own, on top of the share dialog: the fields are the same ones a new link is
    made in, and without a window around them it is not clear that saving changes the link
    somebody is already holding rather than making a second one.
-->
<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";
    import type {ShareDialogViewModel} from "$lib/app/mails/detail_panel/share/ShareDialogViewModel.svelte";
    import {ShareFormViewModel} from "$lib/app/mails/detail_panel/share/ShareFormViewModel.svelte";
    import ShareForm from "$lib/app/mails/detail_panel/share/ShareForm.svelte";

    let {
        viewModel,
        emailId,
    }: {
        /** The dialog around this one; `editing` is what opens and closes this window. */
        viewModel: ShareDialogViewModel,
        emailId: string,
    } = $props();

    const {shares} = useRepositories();

    /**
     * The link being edited, kept next to the form.
     *
     * The id, not the name, is what the write goes to: the link that was handed out keeps working
     * under a new name, a new date or a new password.
     */
    let editedId: string | null = null;

    /** The same form the dialog behind this one drives; what differs is that this writes to an id. */
    const form = new ShareFormViewModel((draft) => {
        if (!editedId) throw new Error("No share is being edited");
        return shares.update(emailId, editedId, draft);
    });

    // Opening fills the form with the link as it stands. Not on closing: the window animates out,
    // and emptying the fields under it would show that happening.
    $effect(() => {
        const target = viewModel.editing;
        if (!target) return;

        editedId = target.id;
        form.reset(target);
    });

    function close() {
        if (form.saving) return;
        viewModel.editing = null;
    }

    /** Saves and closes; a failure leaves the window standing with the reason on it. */
    async function save() {
        const saved = await form.submit();
        if (!saved) return;

        viewModel.editing = null;
        await viewModel.load();
    }
</script>

<Dialog.Root
        open={viewModel.editing !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <!-- The same width as the dialog it opens over: it is the same form. -->
    <Dialog.Content class="sm:max-w-xl">
        <Dialog.Header>
            <Dialog.Title>{$_("mails.share.edit.title")}</Dialog.Title>
            <Dialog.Description>{$_("mails.share.edit.description")}</Dialog.Description>
        </Dialog.Header>

        <!-- A form, so Enter in the name field saves, like the footer button does. -->
        <form
                class="flex flex-col gap-2"
                onsubmit={(event) => {
                    event.preventDefault();
                    void save();
                }}
        >
            <ShareForm viewModel={form} />

            <Dialog.Footer>
                <Button type="button" variant="secondary" disabled={form.saving} onclick={close}>
                    {$_("mails.share.form.cancel")}
                </Button>
                <Button type="submit" disabled={!form.canSubmit}>
                    {$_("mails.share.form.save")}
                </Button>
            </Dialog.Footer>
        </form>
    </Dialog.Content>
</Dialog.Root>
