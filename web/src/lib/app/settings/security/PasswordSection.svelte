<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {CheckCircleIcon, EnvelopeSimpleIcon, WarningCircleIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
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
        try {
            isSet = await passwordRepository.isSet();
            failed = false;
        } catch {
            failed = true;
        }
    }

    onMount(() => void load());
</script>

<section class="flex flex-col gap-1">
    <h2 class="text-xl">{$_("settings.security.password.title")}</h2>

    {#if failed}
        <span class="flex flex-row gap-1 items-center text-sm text-muted-foreground">
            <WarningCircleIcon class="size-4" />
            {$_("settings.security.password.loadFailed")}
        </span>
    {:else if isSet === null}
        <div class="h-6 w-6 animate-spin rounded-full border-4 border-solid border-primary border-t-transparent"></div>
    {:else}
        <p class="flex flex-row gap-2 items-center text-sm text-muted-foreground">
            {#if isSet}
                <CheckCircleIcon class="size-4 shrink-0" />
                {$_("settings.security.password.statusSet")}
            {:else}
                <EnvelopeSimpleIcon class="size-4 shrink-0" />
                {$_("settings.security.password.statusUnset")}
            {/if}
        </p>

        <div class="flex flex-row flex-wrap gap-2 pt-2">
            <Button variant="outline" onclick={() => (editing = true)}>
                {isSet ? $_("settings.security.password.change.action") : $_("settings.security.password.set.action")}
            </Button>
            {#if isSet}
                <Button variant="destructive" onclick={() => (removing = true)}>
                    {$_("settings.security.password.remove.action")}
                </Button>
            {/if}
        </div>
    {/if}
</section>

<!-- Only once the state is known: the dialog's wording and fields depend on it. -->
{#if isSet !== null}
    <PasswordDialog bind:open={editing} {isSet} onsaved={() => (isSet = true)} />
    <RemovePasswordDialog bind:open={removing} onremoved={() => (isSet = false)} />
{/if}
