<script lang="ts">
    import * as AlertDialog from "$lib/components/ui/alert-dialog";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {WarningCircleIcon} from "phosphor-svelte";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    let {
        entry = $bindable(null),
        onDeleted,
    }: {
        /** The entry to forget; null closes the dialog. */
        entry: KnowledgeEntry | null,
        onDeleted?: () => void,
    } = $props();

    const {knowledge} = useRepositories();

    let state: {type: "idle"} | {type: "deleting"} | {type: "failed"} = $state({type: "idle"});

    // `$derived.by`, not `$derived`: read straight after the declaration, `state` is still
    // narrowed to the type it was initialised with, and the comparison looks impossible.
    const deleting = $derived.by(() => state.type === "deleting");

    async function confirm() {
        const target = entry;
        if (!target || deleting) return;

        state = {type: "deleting"};
        try {
            await knowledge.remove(target.id);
            state = {type: "idle"};
            entry = null;
            onDeleted?.();
        } catch {
            // The entry is still there, so the dialog stays open with the reason on it.
            state = {type: "failed"};
        }
    }

    function close() {
        if (deleting) return;
        state = {type: "idle"};
        entry = null;
    }
</script>

<!--
  An alert dialog, not a plain one: this asks to destroy something, and that is the difference
  between focus starting on "cancel" and starting on the button that does it.
-->
<AlertDialog.Root
        open={entry !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <AlertDialog.Content>
        {#if entry}
            <AlertDialog.Header>
                <AlertDialog.Title>{$_("settings.knowledge.delete.title")}</AlertDialog.Title>
                <!-- The name, so the decision is about the entry in front of them and not "an entry". -->
                <AlertDialog.Description>
                    {$_("settings.knowledge.delete.description", {values: {name: entry.name}})}
                </AlertDialog.Description>
            </AlertDialog.Header>

            <div class="flex flex-col gap-2 text-sm">
                <p class="text-muted-foreground">{$_("settings.knowledge.delete.irreversible")}</p>

                <div aria-live="polite" class="min-h-5">
                    {#if state.type === "deleting"}
                        <div class="text-muted-foreground flex flex-row items-start gap-2">
                            <Spinner class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("settings.knowledge.delete.deleting")}</span>
                        </div>
                    {:else if state.type === "failed"}
                        <div class="text-destructive flex flex-row items-start gap-2">
                            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("settings.knowledge.delete.failed")}</span>
                        </div>
                    {/if}
                </div>
            </div>

            <AlertDialog.Footer>
                <AlertDialog.Cancel disabled={deleting}>
                    {$_("settings.knowledge.delete.cancel")}
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
                    {$_("settings.knowledge.delete.confirm")}
                </AlertDialog.Action>
            </AlertDialog.Footer>
        {/if}
    </AlertDialog.Content>
</AlertDialog.Root>
