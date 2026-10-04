<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {CheckCircleIcon, DeviceMobileIcon, TrashIcon, WarningCircleIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {Switch} from "$lib/components/ui/switch";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {useRepositories} from "$lib/repository/repositories";
    import type {TotpStatus} from "$lib/repository/TotpRepository";
    import TotpSetupDialog from "./TotpSetupDialog.svelte";
    import TotpRemoveDialog from "./TotpRemoveDialog.svelte";

    const {totp: totpRepository} = useRepositories();

    /** Null until the server answered. */
    let status: TotpStatus | null = $state(null);
    let failed = $state(false);
    let savingFallback = $state(false);
    let fallbackFailed = $state(false);

    let settingUp = $state(false);
    let removing = $state(false);

    const id = $props.id();

    async function load() {
        failed = false;
        try {
            status = await totpRepository.status();
        } catch {
            failed = true;
        }
    }

    /** Shown switched right away, and put back if the server does not take it. */
    async function setEmailFallback(enabled: boolean) {
        if (!status || savingFallback) return;
        const before = status.emailFallback;
        status.emailFallback = enabled;
        savingFallback = true;
        fallbackFailed = false;
        try {
            await totpRepository.setEmailFallback(enabled);
        } catch {
            status.emailFallback = before;
            fallbackFailed = true;
        } finally {
            savingFallback = false;
        }
    }

    onMount(() => void load());
</script>

<section class="flex flex-col gap-3">
    <h2 class="text-xl">{$_("settings.security.twoFactor.title")}</h2>

    <div class="flex flex-row flex-wrap items-center gap-4">
        <!-- Like the password: the tile carries the state. -->
        <div
                class={[
                    "flex size-10 shrink-0 items-center justify-center rounded-lg transition-colors",
                    status?.isEnabled ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
                ]}
        >
            <DeviceMobileIcon class="size-5" weight={status?.isEnabled ? "fill" : "regular"} />
        </div>

        <div class="flex min-w-0 flex-1 flex-col">
            <span class="font-medium">{$_("settings.security.totp.title")}</span>

            {#if failed}
                <span class="flex flex-row items-center gap-1 text-sm text-destructive">
                    <WarningCircleIcon class="size-4 shrink-0" />
                    {$_("settings.security.totp.loadFailed")}
                    <button class="cursor-pointer underline underline-offset-2" onclick={() => void load()}>
                        {$_("settings.security.totp.retry")}
                    </button>
                </span>
            {:else if status === null}
                <Skeleton class="h-4 w-48" />
            {:else if status.isEnabled}
                <span class="flex flex-row items-center gap-1.5 text-sm text-muted-foreground">
                    <CheckCircleIcon class="size-4 shrink-0 text-primary" weight="fill" />
                    {$_("settings.security.totp.statusEnabled")}
                </span>
            {:else}
                <span class="text-sm text-muted-foreground">{$_("settings.security.totp.statusDisabled")}</span>
            {/if}
        </div>

        {#if status === null}
            {#if !failed}
                <Skeleton class="h-9 w-28 rounded-4xl" />
            {/if}
        {:else if status.isEnabled}
            <Tooltip.Root>
                <Tooltip.Trigger>
                    {#snippet child({props})}
                        <Button
                                {...props}
                                variant="ghost"
                                size="icon"
                                class="text-muted-foreground hover:text-destructive"
                                aria-label={$_("settings.security.totp.remove.action")}
                                onclick={() => (removing = true)}
                        >
                            <TrashIcon />
                        </Button>
                    {/snippet}
                </Tooltip.Trigger>
                <Tooltip.Content>{$_("settings.security.totp.remove.action")}</Tooltip.Content>
            </Tooltip.Root>
        {:else}
            <Button onclick={() => (settingUp = true)}>{$_("settings.security.totp.setup.action")}</Button>
        {/if}
    </div>

    <!-- Only with an app: until there is one, the mailed code is not standing in for anything. -->
    {#if status?.isEnabled}
        <div class="flex flex-row items-start gap-4 ps-14">
            <label for={"totp-fallback-" + id} class="flex min-w-0 flex-1 cursor-pointer flex-col">
                <span class="text-sm font-medium">{$_("settings.security.totp.fallback.title")}</span>
                <span class="text-sm text-muted-foreground">{$_("settings.security.totp.fallback.description")}</span>
                {#if fallbackFailed}
                    <span role="alert" class="text-sm text-destructive">{$_("settings.security.totp.errors.failed")}</span>
                {/if}
            </label>
            <Switch
                    id={"totp-fallback-" + id}
                    class="mt-0.5"
                    checked={status.emailFallback}
                    disabled={savingFallback}
                    onCheckedChange={(checked) => void setEmailFallback(checked)}
            />
        </div>
    {/if}
</section>

{#if status !== null}
    <TotpSetupDialog bind:open={settingUp} onenabled={() => void load()} />
    <TotpRemoveDialog bind:open={removing} onremoved={() => void load()} />
{/if}
