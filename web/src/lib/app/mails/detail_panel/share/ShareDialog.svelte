<!--
    Where a mail is handed out: the form that makes a link, and the links that are already out.

    Both in one dialog rather than a screen of its own, because they are one thought -- somebody
    who opens this either wants a link or wants to take one back, and the list is what says which
    of the two they are looking at. Changing a link that is already out is a window of its own:
    see ShareEditDialog.
-->
<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {LinkSimpleIcon} from "phosphor-svelte";
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";
    import {ShareDialogViewModel} from "$lib/app/mails/detail_panel/share/ShareDialogViewModel.svelte";
    import {ShareFormViewModel} from "$lib/app/mails/detail_panel/share/ShareFormViewModel.svelte";
    import ShareForm from "$lib/app/mails/detail_panel/share/ShareForm.svelte";
    import ShareList from "$lib/app/mails/detail_panel/share/ShareList.svelte";
    import ShareEditDialog from "$lib/app/mails/detail_panel/share/ShareEditDialog.svelte";
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

    const {shares} = useRepositories();
    // The dialog is mounted per opening and lives as long as the mail it was opened on, so the
    // id these capture here is the id they have for their whole life.
    // svelte-ignore state_referenced_locally
    const viewModel = new ShareDialogViewModel(emailId, shares);
    // svelte-ignore state_referenced_locally
    const form = new ShareFormViewModel((draft) => shares.create(emailId, draft));

    /** Says the clipboard refused, which is a thing a browser does without asking the user. */
    let copyFailed = $state(false);

    onMount(() => {
        const abort = new AbortController();
        void viewModel.load(abort.signal);

        return () => {
            abort.abort();
            viewModel.dispose();
        };
    });

    /**
     * Writes the link and puts it on the clipboard straight away.
     *
     * That is what the button says it does: a link nobody copied is a link nobody has, and the
     * list below keeps a copy button for the ones made earlier.
     */
    async function save() {
        const share = await form.submit();
        if (!share) return;

        const url = shareUrl(share.id, location.origin, subjectFor(share, subject));
        copyFailed = !(await viewModel.copy(share, url));
        // Empty again, so the next link is a new one and not an edit of what was just made.
        form.reset();
        await viewModel.load();
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
            <ShareForm viewModel={form} note={copyFailed ? $_("mails.share.list.copyFailed") : null} />

            <Button type="submit" class="w-fit self-end" disabled={!form.canSubmit}>
                <LinkSimpleIcon/>
                {$_("mails.share.form.create")}
            </Button>
        </form>

        <div class="h-px w-full bg-muted my-3"></div>

        <ShareList {viewModel} {subject} onCopyFailed={(failed) => (copyFailed = failed)} />
    </Dialog.Content>
</Dialog.Root>

<!-- Outside the dialog above: they are two windows, and this one is what says an edit is an edit. -->
<ShareEditDialog {viewModel} {emailId} />
<DeleteShareDialog {viewModel} />
