<script lang="ts">
    import QRCode from '@castlenine/svelte-qrcode';
    import {useRepositories} from "$lib/repository/repositories.ts";
    import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository.ts";
    import {onMount} from "svelte";
    import {Button} from "$lib/components/ui/button";
    import {CheckIcon, CopyIcon} from "phosphor-svelte";

    const {deviceSettingsRepository} = useRepositories();

    let currentAuthQrState: AuthCodeState = $state({type: "loading"})

    let renewAuthCodeTimeout: ReturnType<typeof setTimeout> | null = null;

    let showCopyCheckmark = $state(false);
    let hideCopyCheckmarkTimeout: ReturnType<typeof setTimeout> | null = null;

    /**
     * The whole `overmail://` url, not the bare code: the app's "Code eingeben" field reads either,
     * but only the url carries the server it belongs to -- a bare code signs in to whichever server
     * the app was built against. See `LoginCode.parse`.
     */
    function copyAuthCode() {
        if (currentAuthQrState.type !== "ready") return;
        navigator.clipboard.writeText(currentAuthQrState.url);

        if (hideCopyCheckmarkTimeout) clearTimeout(hideCopyCheckmarkTimeout);
        showCopyCheckmark = true;
        hideCopyCheckmarkTimeout = setTimeout(() => {
            showCopyCheckmark = false;
            hideCopyCheckmarkTimeout = null;
        }, 2000);
    }

    function renewAuthCode() {
        currentAuthQrState = {type: "loading"}
        // The old code is dead the moment a new one is asked for, so a "copied" from it would lie.
        showCopyCheckmark = false;
        deviceSettingsRepository.fetchAuthCode().then((authCodeState) => {
            currentAuthQrState = authCodeState;
            if (authCodeState.type !== "ready") return;
            if (renewAuthCodeTimeout) {
                clearTimeout(renewAuthCodeTimeout);
            }

            const now = new Date();
            const expiresAt = authCodeState.validUntil;
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
            if (hideCopyCheckmarkTimeout) {
                clearTimeout(hideCopyCheckmarkTimeout);
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
            <div class="flex flex-col items-start gap-2">
                <span>Scanne diesen Code mit der Overmail-App, um dich anzumelden.</span>
                <a href="https://github.com/overmail/overmail/releases/latest" target="_blank" class="text-primary hover:underline">Overmail-App herunterladen</a>
                <!-- For a device that cannot scan, and for testing the app's code input by hand. -->
                <Button
                        variant="outline"
                        size="sm"
                        disabled={currentAuthQrState.type !== "ready"}
                        onclick={copyAuthCode}
                >
                    {#if showCopyCheckmark}
                        <CheckIcon />
                        Kopiert
                    {:else}
                        <CopyIcon />
                        Link kopieren
                    {/if}
                </Button>
            </div>
        </div>
    </div>
</div>