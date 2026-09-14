<!--
    The question in front of a deleted view. Held open by the view it is about, so the list can
    put any row into it and there is only one dialog for all of them.

    Shift in the row's menu goes around this, see `ViewList.svelte` -- what happens then is the
    same call, only without the asking.
-->
<script lang="ts">
    import {WarningCircleIcon} from "phosphor-svelte";
    import * as AlertDialog from "$lib/components/ui/alert-dialog";
    import {Spinner} from "$lib/components/ui/spinner";
    import {useRepositories} from "$lib/repository/repositories";
    import type {View} from "$lib/repository/ViewSocket";

    let {
        view = $bindable(null),
        onDeleted,
    }: {
        /** The view to delete; null closes the dialog. */
        view: View | null;
        onDeleted?: (view: View) => void;
    } = $props();

    const {views} = useRepositories();

    let state: {type: "idle"} | {type: "deleting"} | {type: "failed"} = $state({type: "idle"});

    // `$derived.by`, not `$derived`: read straight after the declaration, `state` is still
    // narrowed to the type it was initialised with, and the comparison looks impossible.
    const deleting = $derived.by(() => state.type === "deleting");

    async function confirm() {
        const target = view;
        if (!target || deleting) return;

        state = {type: "deleting"};
        try {
            await views.remove(target.id);
            state = {type: "idle"};
            view = null;
            onDeleted?.(target);
        } catch {
            // The view is still there, so the dialog stays open with the reason on it.
            state = {type: "failed"};
        }
    }

    function close() {
        if (deleting) return;

        state = {type: "idle"};
        view = null;
    }
</script>

<!--
  An alert dialog, not a plain one: this asks to destroy something, and that is the difference
  between focus starting on "cancel" and starting on the button that does it.
-->
<AlertDialog.Root
        open={view !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <AlertDialog.Content>
        {#if view}
            <AlertDialog.Header>
                <AlertDialog.Title>Ansicht löschen?</AlertDialog.Title>
                <!-- The name, so the decision is about the view in front of them, not "a view". -->
                <AlertDialog.Description>
                    „{view.name}“ wird gelöscht.
                </AlertDialog.Description>
            </AlertDialog.Header>

            <div class="flex flex-col gap-2 text-sm">
                <!-- Said outright: a view is a way of looking at mail, and nobody should wonder
                     whether the mails under it go with it. -->
                <p class="text-muted-foreground">
                    Die Mails bleiben, nur die Ansicht verschwindet.
                </p>

                <div aria-live="polite" class="min-h-5">
                    {#if state.type === "deleting"}
                        <div class="text-muted-foreground flex flex-row items-start gap-2">
                            <Spinner class="mt-0.5 size-4 shrink-0"/>
                            <span>Wird gelöscht…</span>
                        </div>
                    {:else if state.type === "failed"}
                        <div class="text-destructive flex flex-row items-start gap-2">
                            <WarningCircleIcon class="mt-0.5 size-4 shrink-0"/>
                            <span>Die Ansicht konnte nicht gelöscht werden.</span>
                        </div>
                    {/if}
                </div>
            </div>

            <AlertDialog.Footer>
                <AlertDialog.Cancel disabled={deleting}>Abbrechen</AlertDialog.Cancel>
                <!-- Not a `Cancel`, so the dialog stays open when the delete fails. -->
                <AlertDialog.Action
                        variant="destructive"
                        disabled={deleting}
                        onclick={(event) => {
                            event.preventDefault();
                            void confirm();
                        }}
                >
                    Löschen
                </AlertDialog.Action>
            </AlertDialog.Footer>
        {/if}
    </AlertDialog.Content>
</AlertDialog.Root>
