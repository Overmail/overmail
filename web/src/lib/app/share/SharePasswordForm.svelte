<!--
    The password a share asks for, on the page itself rather than in a dialog: it is the whole
    content of the page until it is typed, and a dialog over an empty page would be a window over
    nothing.
-->
<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {Label} from "$lib/components/ui/label";
    import {Spinner} from "$lib/components/ui/spinner";
    import {LockSimpleIcon, WarningCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import type {SharePageViewModel} from "$lib/app/share/SharePageViewModel.svelte";

    let {viewModel}: {viewModel: SharePageViewModel} = $props();

    let id = $props.id();
    let field: HTMLInputElement | null = $state(null);

    // The field is what the page is for while it is locked, so the keyboard starts there.
    $effect(() => {
        field?.focus();
    });
</script>

<form
        class="border-border flex flex-col gap-3 rounded-lg border p-4"
        onsubmit={(event) => {
            event.preventDefault();
            void viewModel.unlock();
        }}
>
    <div class="flex flex-row items-center gap-2">
        <LockSimpleIcon class="size-4 shrink-0" />
        <h2 class="text-sm font-medium">{$_("share.locked.title")}</h2>
    </div>

    <p class="text-muted-foreground text-sm">{$_("share.locked.description")}</p>

    <div class="flex flex-col gap-1">
        <Label for={"share-password-" + id}>{$_("share.locked.password")}</Label>
        <Input
                bind:ref={field}
                id={"share-password-" + id}
                type="password"
                autocomplete="off"
                value={viewModel.password}
                oninput={(event) => viewModel.setPassword(event.currentTarget.value)}
        />
    </div>

    <div aria-live="polite" class="min-h-5 text-sm">
        {#if viewModel.unlocking}
            <div class="text-muted-foreground flex flex-row items-start gap-2">
                <Spinner class="mt-0.5 size-4 shrink-0" />
                <span>{$_("share.locked.unlocking")}</span>
            </div>
        {:else if viewModel.unlockState.type === "wrong"}
            <span class="text-destructive">{$_("share.locked.wrong")}</span>
        {:else if viewModel.unlockState.type === "failed"}
            <div class="text-destructive flex flex-row items-start gap-2">
                <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                <span>{$_("share.locked.failed")}</span>
            </div>
        {/if}
    </div>

    <Button type="submit" class="w-fit" disabled={!viewModel.canUnlock}>
        {$_("share.locked.submit")}
    </Button>
</form>
