<!--
    Where a share link lands: one mail, read by somebody who has no account here.

    Public, so the layout puts no shell around it (see the root layout) and nothing on this page
    reaches for a session. Nothing is live either: a shared mail does not change under its reader,
    so it is read once, and again when the password is typed.
-->
<script lang="ts">
    import {page} from "$app/state";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {ShareNetworkIcon, WarningCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";
    import {parseShareId} from "$lib/app/mails/detail_panel/share/sharePath";
    import {SharePageViewModel} from "$lib/app/share/SharePageViewModel.svelte";
    import SharePasswordForm from "$lib/app/share/SharePasswordForm.svelte";
    import SharedMail from "$lib/app/share/SharedMail.svelte";

    const {sharedEmail} = useRepositories();

    /** The id at the end of the url; what is in front of it is the subject, for the reader. */
    const shareId = $derived(parseShareId(page.params.share));

    /**
     * One view model per link. A url without an id in it is not a link this app made, and the
     * page says the same as for one nobody has -- there is nothing else it could know.
     */
    const viewModel = $derived(
        shareId === null ? null : new SharePageViewModel(shareId, sharedEmail)
    );

    $effect(() => {
        const current = viewModel;
        if (!current) return;

        const abort = new AbortController();
        void current.load(abort.signal);
        return () => abort.abort();
    });

    const state = $derived(viewModel?.state ?? {type: "missing" as const});
</script>

<svelte:head>
    <title>{viewModel?.shared?.metadata?.subject ?? $_("app.name")}</title>
    <!-- A shared link is not for search engines, whoever it was handed to. -->
    <meta name="robots" content="noindex, nofollow" />
</svelte:head>

<main class="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-10">
    <div class="text-muted-foreground flex flex-row items-center gap-2 text-sm">
        <ShareNetworkIcon class="size-4 shrink-0" />
        <span>{$_("share.notice")}</span>
    </div>

    {#if state.type === "loading"}
        <div class="flex flex-col gap-3">
            <Skeleton class="h-6 w-2/3" />
            <Skeleton class="h-4 w-1/3" />
            <Skeleton class="h-4 w-full" />
            <Skeleton class="h-4 w-10/12" />
        </div>
    {:else if state.type === "shown" && viewModel}
        <SharedMail shared={state.shared} />

        {#if viewModel.locked}
            <SharePasswordForm {viewModel} />
        {/if}
    {:else}
        <!-- The three ways a link can be a dead end, each worded as what it is: one ran out, one
             never was, and one is this app failing rather than the link. -->
        <div class="border-border flex flex-col gap-2 rounded-lg border p-4">
            <div class="flex flex-row items-center gap-2">
                <WarningCircleIcon class="size-4 shrink-0" />
                <h1 class="text-sm font-medium">{$_(`share.${state.type}.title`)}</h1>
            </div>

            <p class="text-muted-foreground text-sm">{$_(`share.${state.type}.description`)}</p>

            {#if state.type === "failed" && viewModel}
                <Button variant="secondary" class="mt-2 w-fit" onclick={() => viewModel.load()}>
                    {$_("share.failed.retry")}
                </Button>
            {/if}
        </div>
    {/if}
</main>
