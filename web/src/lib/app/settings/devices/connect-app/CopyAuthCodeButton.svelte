<script lang="ts">
    import {onDestroy} from "svelte";
    import {Button} from "$lib/components/ui/button";
    import {CheckIcon, CopyIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    /** How long the button says "Copied" before it offers to copy again. */
    const COPIED_FLASH_MS = 2000;

    /** The link the app would scan; null while there is none, which disables the button. */
    let {url}: {url: string | null} = $props();

    let copied = $state(false);
    let copyFailed = $state(false);
    let copiedTimeout: ReturnType<typeof setTimeout> | null = null;

    async function copy() {
        if (url === null) return;
        try {
            await navigator.clipboard.writeText(url);
        } catch {
            copied = false;
            copyFailed = true;
            return;
        }
        copyFailed = false;
        copied = true;
        if (copiedTimeout) clearTimeout(copiedTimeout);
        copiedTimeout = setTimeout(() => {
            copied = false;
            copiedTimeout = null;
        }, COPIED_FLASH_MS);
    }

    onDestroy(() => {
        if (copiedTimeout) clearTimeout(copiedTimeout);
    });
</script>

<Button class="self-start" variant="outline" disabled={url === null} onclick={() => void copy()}>
    {#if copied}
        <CheckIcon />
        {$_("settings.devices.copied")}
    {:else}
        <CopyIcon />
        {$_("settings.devices.copy")}
    {/if}
</Button>
{#if copyFailed}
    <span class="text-sm text-destructive">{$_("settings.devices.copyFailed")}</span>
{/if}
