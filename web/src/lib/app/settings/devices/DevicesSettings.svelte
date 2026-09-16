<script lang="ts">
    import QRCode from '@castlenine/svelte-qrcode';
    import {useRepositories} from "$lib/repository/repositories.ts";
    import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository.ts";
    import {onMount} from "svelte";

    const {deviceSettingsRepository} = useRepositories();

    let currentAuthQrState: AuthCodeState = $state({type: "loading"})

    let renewAuthCodeTimeout: ReturnType<typeof setTimeout> | null = null;

    function renewAuthCode() {
        currentAuthQrState = {type: "loading"}
        deviceSettingsRepository.fetchAuthCode().then((authCodeState) => {
            currentAuthQrState = authCodeState;
            if (authCodeState.type !== "ready") return;
            if (renewAuthCodeTimeout) {
                clearTimeout(renewAuthCodeTimeout);
            }

            const now = new Date();
            const expiresAt = currentAuthQrState.validUntil;
            const timeLeft = expiresAt.getTime() - now.getTime();
            if (timeLeft > 0) {
                renewAuthCodeTimeout = setTimeout(renewAuthCode, timeLeft);
            }
        });
    }

    onMount(() => {
        renewAuthCode();

        return () => {
            if (renewAuthCodeTimeout) {
                clearTimeout(renewAuthCodeTimeout);
            }
        };
    });
</script>

<div class="flex min-w-0 flex-1 flex-col grow">
    <div class="flex flex-col gap-1">
        <h2 class="text-xl">Overmail-App verbinden</h2>

        <div class="flex flex-row flrx-wrap gap-4 items-center">
            <div class="size-56">
                {#if currentAuthQrState.type === "loading"}
                    <div class="flex flex-1 items-center justify-center w-full h-full bg-accent rounded-md">
                        <div class="h-8 w-8 animate-spin rounded-full border-4 border-solid border-primary border-t-transparent"></div>
                    </div>
                {:else if currentAuthQrState.type === "ready"}
                    <QRCode data={currentAuthQrState.url} size={224} />
                {/if}
            </div>
            <div class="flex flex-col gap-2">
                <span>Scanne diesen Code mit der Overmail-App, um dich anzumelden.</span>
                <a href="https://github.com/overmail/overmail/releases/latest" target="_blank" class="text-primary hover:underline">Overmail-App herunterladen</a>
            </div>
        </div>
    </div>
</div>