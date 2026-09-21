<script lang="ts">
    import QRCode from '@castlenine/svelte-qrcode';
    import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository";

    let {state}: {state: AuthCodeState} = $props();
</script>

<div class="size-48">
    {#if state.type === "loading"}
        <div class="flex flex-1 items-center justify-center w-full h-full bg-accent rounded-md">
            <div class="h-8 w-8 animate-spin rounded-full border-4 border-solid border-primary border-t-transparent"></div>
        </div>
    {:else if state.type === "ready"}
        <QRCode data={state.url} size={192} />
    {/if}
</div>
