<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories.ts";
    import {SessionsViewModel} from "./SessionsViewModel.svelte";
    import SessionList from "./SessionList.svelte";

    const {sessions: sessionsRepository} = useRepositories();
    const viewModel = new SessionsViewModel((signal) => sessionsRepository.list(signal));

    onMount(() => {
        void viewModel.poll();
        return () => viewModel.dispose();
    });
</script>

<section class="flex flex-col gap-1">
    <h2 class="text-xl">{$_("settings.devices.sessions.title")}</h2>
    {#if viewModel.failed}
        <span class="text-sm text-destructive">{$_("settings.devices.sessions.loadFailed")}</span>
    {/if}
    {#if viewModel.sessions === null}
        {#if !viewModel.failed}
            <div class="h-6 w-6 animate-spin rounded-full border-4 border-solid border-primary border-t-transparent"></div>
        {/if}
    {:else}
        <SessionList sessions={viewModel.sessions} />
    {/if}
</section>
