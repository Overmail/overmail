<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {CheckCircleIcon, KeyIcon, TrashIcon, WarningCircleIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {useRepositories} from "$lib/repository/repositories";
    import PasswordDialog from "./PasswordDialog.svelte";
    import RemovePasswordDialog from "./RemovePasswordDialog.svelte";

    const {password: passwordRepository} = useRepositories();

    /** Null until the server answered. */
    let isSet: boolean | null = $state(null);
    let failed = $state(false);

    let editing = $state(false);
    let removing = $state(false);

    async function load() {
        failed = false;
        try {
            isSet = await passwordRepository.isSet();
        } catch {
            failed = true;
        }
    }

    onMount(() => void load());
</script>

<section class="flex flex-col gap-3">
    <h2 class="text-xl">{$_("settings.security.signIn.title")}</h2>

    <div class="flex flex-row flex-wrap items-center gap-4">
        <!-- The tile carries the state: tinted once a password is set, plain while there is none. -->
        <div
                class={[
                    "flex size-10 shrink-0 items-center justify-center rounded-lg transition-colors",
                    isSet ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
                ]}
        >
            <KeyIcon class="size-5" weight={isSet ? "fill" : "regular"} />
        </div>

        <div class="flex min-w-0 flex-1 flex-col">
            <span class="font-medium">{$_("settings.security.password.title")}</span>

            {#if failed}
                <span class="flex flex-row items-center gap-1 text-sm text-destructive">
                    <WarningCircleIcon class="size-4 shrink-0" />
                    {$_("settings.security.password.loadFailed")}
                    <button class="cursor-pointer underline underline-offset-2" onclick={() => void load()}>
                        {$_("settings.security.password.retry")}
                    </button>
                </span>
            {:else if isSet === null}
                <Skeleton class="h-4 w-48" />
            {:else if isSet}
                <span class="flex flex-row items-center gap-1.5 text-sm text-muted-foreground">
                    <CheckCircleIcon class="size-4 shrink-0 text-primary" weight="fill" />
                    {$_("settings.security.password.statusSet")}
                </span>
            {:else}
                <span class="text-sm text-muted-foreground">{$_("settings.security.password.statusUnset")}</span>
            {/if}
        </div>

        {#if isSet === null}
            {#if !failed}
                <Skeleton class="h-9 w-28 rounded-4xl" />
            {/if}
        {:else}
            <div class="flex flex-row items-center gap-1">
                <Button variant={isSet ? "outline" : "default"} onclick={() => (editing = true)}>
                    {isSet ? $_("settings.security.password.change.action") : $_("settings.security.password.set.action")}
                </Button>
                {#if isSet}
                    <Tooltip.Root>
                        <Tooltip.Trigger>
                            {#snippet child({props})}
                                <Button
                                        {...props}
                                        variant="ghost"
                                        size="icon"
                                        class="text-muted-foreground hover:text-destructive"
                                        aria-label={$_("settings.security.password.remove.action")}
                                        onclick={() => (removing = true)}
                                >
                                    <TrashIcon />
                                </Button>
                            {/snippet}
                        </Tooltip.Trigger>
                        <Tooltip.Content>{$_("settings.security.password.remove.action")}</Tooltip.Content>
                    </Tooltip.Root>
                {/if}
            </div>
        {/if}
    </div>
</section>

<!-- Only once the state is known: the dialog's wording and fields depend on it. -->
{#if isSet !== null}
    <PasswordDialog bind:open={editing} {isSet} onsaved={() => (isSet = true)} />
    <RemovePasswordDialog bind:open={removing} onremoved={() => (isSet = false)} />
{/if}
