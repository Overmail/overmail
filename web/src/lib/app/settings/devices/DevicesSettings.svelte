<script lang="ts">
    import QRCode from '@castlenine/svelte-qrcode';
    import {useRepositories} from "$lib/repository/repositories.ts";
    import type {AuthCodeState} from "$lib/repository/DevicesSettingsRepository.ts";
    import {onMount} from "svelte";
    import {Button} from "$lib/components/ui/button";
    import {CheckIcon, CopyIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    const COPIED_FLASH_MS = 2000;

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
            const expiresAt = authCodeState.validUntil;
            const timeLeft = expiresAt.getTime() - now.getTime();
            if (timeLeft > 0) {
                renewAuthCodeTimeout = setTimeout(renewAuthCode, timeLeft);
            }
        });
    }

    let copied = $state(false);
    let copyFailed = $state(false);
    let copiedTimeout: ReturnType<typeof setTimeout> | null = null;

    async function copyAuthCode() {
        if (currentAuthQrState.type !== "ready") return;
        try {
            await navigator.clipboard.writeText(currentAuthQrState.url);
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

    onMount(() => {
        renewAuthCode();

        return () => {
            if (renewAuthCodeTimeout) {
                clearTimeout(renewAuthCodeTimeout);
            }
            if (copiedTimeout) {
                clearTimeout(copiedTimeout);
            }
        };
    });
</script>

<div class="flex min-w-0 flex-1 flex-col grow">
    <div class="flex flex-col gap-1">
        <h2 class="text-xl">{$_("settings.devices.title")}</h2>

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
                <span>{$_("settings.devices.scan")}</span>
                <a href="https://github.com/overmail/overmail/releases/latest" target="_blank" class="text-primary hover:underline">{$_("settings.devices.download")}</a>
                <Button
                        class="self-start"
                        variant="outline"
                        disabled={currentAuthQrState.type !== "ready"}
                        onclick={() => void copyAuthCode()}
                >
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
            </div>
        </div>
    </div>
</div>