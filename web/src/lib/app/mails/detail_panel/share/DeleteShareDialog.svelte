<!--
    Asks before a link is taken back.

    An alert dialog, not a plain one, like the knowledge one: this destroys something somebody
    else may be holding, and that is the difference between focus starting on "cancel" and
    starting on the button that does it.
-->
<script lang="ts">
    import * as AlertDialog from "$lib/components/ui/alert-dialog";
    import {Spinner} from "$lib/components/ui/spinner";
    import {WarningCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import type {ShareDialogViewModel} from "$lib/app/mails/detail_panel/share/ShareDialogViewModel.svelte";

    let {viewModel}: {viewModel: ShareDialogViewModel} = $props();

    let deleting = $state(false);

    const name = $derived(viewModel.deleting?.shareName ?? $_("mails.share.list.unnamed"));

    async function confirm() {
        if (deleting) return;

        deleting = true;
        try {
            await viewModel.confirmDelete();
        } finally {
            deleting = false;
        }
    }
</script>

<AlertDialog.Root
        open={viewModel.deleting !== null}
        onOpenChange={(open) => {
            if (!open && !deleting) viewModel.deleting = null;
        }}
>
    <AlertDialog.Content>
        {#if viewModel.deleting}
            <AlertDialog.Header>
                <AlertDialog.Title>{$_("mails.share.delete.title")}</AlertDialog.Title>
                <!-- The name, so the decision is about the link in front of them, not "a link". -->
                <AlertDialog.Description>
                    {$_("mails.share.delete.description", {values: {name}})}
                </AlertDialog.Description>
            </AlertDialog.Header>

            <div class="flex flex-col gap-2 text-sm">
                <p class="text-muted-foreground">{$_("mails.share.delete.irreversible")}</p>

                <div aria-live="polite" class="min-h-5">
                    {#if deleting}
                        <div class="text-muted-foreground flex flex-row items-start gap-2">
                            <Spinner class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("mails.share.delete.deleting")}</span>
                        </div>
                    {:else if viewModel.deleteFailed}
                        <div class="text-destructive flex flex-row items-start gap-2">
                            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("mails.share.delete.failed")}</span>
                        </div>
                    {/if}
                </div>
            </div>

            <AlertDialog.Footer>
                <AlertDialog.Cancel disabled={deleting}>
                    {$_("mails.share.delete.cancel")}
                </AlertDialog.Cancel>
                <!-- Not a `Cancel`, so the dialog stays open when the delete fails. -->
                <AlertDialog.Action
                        variant="destructive"
                        disabled={deleting}
                        onclick={(event) => {
                            event.preventDefault();
                            void confirm();
                        }}
                >
                    {$_("mails.share.delete.confirm")}
                </AlertDialog.Action>
            </AlertDialog.Footer>
        {/if}
    </AlertDialog.Content>
</AlertDialog.Root>
