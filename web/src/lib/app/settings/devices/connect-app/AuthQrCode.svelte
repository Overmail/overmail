<script lang="ts">
    import QRCode from '@castlenine/svelte-qrcode';
    import {ArrowClockwiseIcon, WarningCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {Button} from "$lib/components/ui/button";
    import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository";

    let {state, onretry}: {state: AuthCodeState; onretry: () => void} = $props();
</script>

<div class="size-48">
    {#if state.type === "loading"}
        <div class="flex flex-1 items-center justify-center w-full h-full bg-accent rounded-md">
            <div class="h-8 w-8 animate-spin rounded-full border-4 border-solid border-primary border-t-transparent"></div>
        </div>
    {:else if state.type === "error"}
        <div class="flex flex-col gap-2 items-center justify-center w-full h-full p-4 bg-accent rounded-md text-center">
            <WarningCircleIcon class="size-6 text-muted-foreground" />
            <span class="text-sm text-muted-foreground">{$_("settings.devices.loadFailed")}</span>
            <Button variant="outline" size="sm" onclick={onretry}>
                <ArrowClockwiseIcon />
                {$_("settings.devices.retry")}
            </Button>
        </div>
    {:else}
        <!--
          Blurred until hovered, so a code on an open settings screen cannot be scanned over
          somebody's shoulder. Focusable, so the keyboard can reveal it too.
        -->
        <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
        <div tabindex="0" class="rounded-md outline-none blur-xs opacity-60 transition hover:blur-none focus-visible:blur-none hover:opacity-100 focus-visible:opacity-100">
            <QRCode data={state.url} size={192} />
        </div>
    {/if}
</div>
