<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {WarningCircleIcon} from "phosphor-svelte";
    import {useRepositories} from "$lib/repository/repositories.ts";
    import {SessionsViewModel} from "./SessionsViewModel.svelte";
    import SessionList from "./SessionList.svelte";
    import SignOutDialog from "./SignOutDialog.svelte";
    import type {UserSession} from "$lib/repository/SessionsRepository";

    const {sessions: sessionsRepository} = useRepositories();
    const viewModel = new SessionsViewModel(
        (signal) => sessionsRepository.list(signal),
        (id, signal) => sessionsRepository.revoke(id, signal),
        // Hard, not through the router: this browser's session is gone, so nothing the app holds
        // in memory is worth keeping.
        () => window.location.assign("/"),
    );

    /** This browser's session, while the user is asked whether they really want to sign out. */
    let confirmingSignOut: UserSession | null = $state(null);

    function revoke(session: UserSession) {
        if (session.isCurrentSession) confirmingSignOut = session;
        else void viewModel.revoke(session);
    }

    onMount(() => {
        void viewModel.poll();
        return () => viewModel.dispose();
    });
</script>

<section class="flex flex-col gap-1">
    <h2 class="text-xl">{$_("settings.devices.sessions.title")}</h2>
    {#if viewModel.failed}
        <span class="flex flex-row gap-1 items-center text-sm text-muted-foreground">
            <WarningCircleIcon class="size-4" />
            {$_("settings.devices.sessions.loadFailed")}
        </span>
    {/if}
    {#if viewModel.sessions === null}
        {#if !viewModel.failed}
            <div class="h-6 w-6 animate-spin rounded-full border-4 border-solid border-primary border-t-transparent"></div>
        {/if}
    {:else}
        <SessionList
                sessions={viewModel.sessions}
                revoking={viewModel.revoking}
                onrevoke={revoke}
        />
    {/if}
</section>

<SignOutDialog
        bind:session={confirmingSignOut}
        signingOut={confirmingSignOut !== null && viewModel.revoking.has(confirmingSignOut.id)}
        onconfirm={(session) => viewModel.revoke(session)}
/>
