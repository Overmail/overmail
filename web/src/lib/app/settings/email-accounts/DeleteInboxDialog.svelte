<script lang="ts">
    import * as AlertDialog from "$lib/components/ui/alert-dialog";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {WarningCircleIcon} from "phosphor-svelte";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    let {
        inbox = $bindable(null),
        onDeleted,
    }: {
        /** The mailbox to disconnect; null closes the dialog. */
        inbox: Inbox | null,
        onDeleted?: () => void,
    } = $props();

    const {inboxes: inboxRepository} = useRepositories();

    let state: {type: "idle"} | {type: "deleting"} | {type: "failed"} = $state({type: "idle"});

    async function confirm() {
        const target = inbox;
        if (!target || state.type === "deleting") return;

        state = {type: "deleting"};
        try {
            await inboxRepository.remove(target.id);
            state = {type: "idle"};
            inbox = null;
            onDeleted?.();
        } catch {
            // The mailbox is still connected, so the dialog stays open with the reason on it.
            state = {type: "failed"};
        }
    }

    function close() {
        if (state.type === "deleting") return;
        state = {type: "idle"};
        inbox = null;
    }
</script>

<!--
  An alert dialog, not a plain one: this asks to destroy something, and that is the difference
  between focus starting on "cancel" and starting on the button that does it.
-->
<AlertDialog.Root
        open={inbox !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <AlertDialog.Content>
        {#if inbox}
            <AlertDialog.Header>
                <AlertDialog.Title>{$_("settings.emailAccounts.list.delete.title")}</AlertDialog.Title>
                <AlertDialog.Description>
                    {$_("settings.emailAccounts.list.delete.description", {
                        values: {username: inbox.username, host: inbox.host},
                    })}
                </AlertDialog.Description>
            </AlertDialog.Header>

            <div class="flex flex-col gap-2 text-sm">
                <!--
                  The number, above the button that acts on it: a mailbox with thousands of
                  imported mails behind it is a different decision from one with none.
                -->
                <p>{$_("settings.emailAccounts.list.delete.emails", {values: {count: inbox.emailCount}})}</p>
                <p class="text-muted-foreground">{$_("settings.emailAccounts.list.delete.irreversible")}</p>

                <div aria-live="polite" class="min-h-5">
                    {#if state.type === "deleting"}
                        <div class="text-muted-foreground flex flex-row items-start gap-2">
                            <Spinner class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("settings.emailAccounts.list.delete.deleting")}</span>
                        </div>
                    {:else if state.type === "failed"}
                        <div class="text-destructive flex flex-row items-start gap-2">
                            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("settings.emailAccounts.list.delete.failed")}</span>
                        </div>
                    {/if}
                </div>
            </div>

            <AlertDialog.Footer>
                <AlertDialog.Cancel disabled={state.type === "deleting"}>
                    {$_("settings.emailAccounts.list.delete.cancel")}
                </AlertDialog.Cancel>
                <!-- Not a `Cancel`, so the dialog stays open when the delete fails. -->
                <AlertDialog.Action
                        variant="destructive"
                        disabled={state.type === "deleting"}
                        onclick={(event) => {
                            event.preventDefault();
                            void confirm();
                        }}
                >
                    {$_("settings.emailAccounts.list.delete.confirm")}
                </AlertDialog.Action>
            </AlertDialog.Footer>
        {/if}
    </AlertDialog.Content>
</AlertDialog.Root>
