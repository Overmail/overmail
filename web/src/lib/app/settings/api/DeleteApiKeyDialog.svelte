<script lang="ts">
    import * as AlertDialog from "$lib/components/ui/alert-dialog";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {WarningCircleIcon} from "phosphor-svelte";
    import type {ApiKey} from "$lib/repository/ApiKeysRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    let {
        apiKey = $bindable(null),
        onDeleted,
    }: {
        /** The key to delete; null closes the dialog. */
        apiKey: ApiKey | null,
        onDeleted?: () => void,
    } = $props();

    const {apiKeys} = useRepositories();

    let state: {type: "idle"} | {type: "deleting"} | {type: "failed"} = $state({type: "idle"});

    // `$derived.by`, not `$derived`: read straight after the declaration, `state` is still
    // narrowed to the type it was initialised with, and the comparison looks impossible.
    const deleting = $derived.by(() => state.type === "deleting");

    async function confirm() {
        const target = apiKey;
        if (!target || deleting) return;

        state = {type: "deleting"};
        try {
            await apiKeys.remove(target.id);
            state = {type: "idle"};
            apiKey = null;
            onDeleted?.();
        } catch {
            // The key still works, so the dialog stays open with the reason on it.
            state = {type: "failed"};
        }
    }

    function close() {
        if (deleting) return;
        state = {type: "idle"};
        apiKey = null;
    }
</script>

<AlertDialog.Root
        open={apiKey !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <AlertDialog.Content>
        {#if apiKey}
            <AlertDialog.Header>
                <AlertDialog.Title>{$_("settings.api.delete.title")}</AlertDialog.Title>
                <AlertDialog.Description>
                    {$_("settings.api.delete.description", {values: {name: apiKey.name}})}
                </AlertDialog.Description>
            </AlertDialog.Header>

            <div aria-live="polite" class="min-h-5 text-sm">
                {#if state.type === "deleting"}
                    <div class="text-muted-foreground flex flex-row items-start gap-2">
                        <Spinner class="mt-0.5 size-4 shrink-0" />
                        <span>{$_("settings.api.delete.deleting")}</span>
                    </div>
                {:else if state.type === "failed"}
                    <div class="text-destructive flex flex-row items-start gap-2">
                        <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                        <span>{$_("settings.api.delete.failed")}</span>
                    </div>
                {/if}
            </div>

            <AlertDialog.Footer>
                <AlertDialog.Cancel disabled={deleting}>
                    {$_("settings.api.delete.cancel")}
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
                    {$_("settings.api.delete.confirm")}
                </AlertDialog.Action>
            </AlertDialog.Footer>
        {/if}
    </AlertDialog.Content>
</AlertDialog.Root>
