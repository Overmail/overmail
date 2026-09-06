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
    import * as Empty from "$lib/components/ui/empty";
    import {ClockCountdownIcon, LinkBreakIcon, ShareNetworkIcon, WarningCircleIcon} from "phosphor-svelte";
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

<div class="bg-muted/30 flex min-h-svh w-full flex-col">
    <main class="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8 sm:py-12">
        <!-- A pill rather than a line of grey text: it is a note about the page, not the first
             line of the mail, and it should not read as one. -->
        <div class="text-muted-foreground bg-background flex w-fit flex-row items-center gap-2 rounded-full border py-1 ps-2.5 pe-3 text-xs">
            <ShareNetworkIcon class="size-3.5 shrink-0" />
            <span>{$_("share.notice")}</span>
        </div>

        <!-- The mail on a surface of its own, so the page reads as "here is a mail" rather than
             as text dropped on the background. -->
        <div class="bg-card text-card-foreground rounded-xl border p-6 shadow-xs sm:p-8">
            {#if state.type === "loading"}
                <div class="flex flex-col gap-3" aria-label={$_("share.loading")}>
                    <Skeleton class="h-6 w-2/3" />
                    <Skeleton class="h-4 w-1/3" />
                    <div class="h-4"></div>
                    <Skeleton class="h-4 w-full" />
                    <Skeleton class="h-4 w-11/12" />
                    <Skeleton class="h-4 w-8/12" />
                </div>
            {:else if state.type === "shown" && viewModel}
                <div class="flex flex-col gap-6">
                    <SharedMail shared={state.shared} />

                    {#if viewModel.locked}
                        <SharePasswordForm {viewModel} />
                    {/if}
                </div>
            {:else}
                <!-- The three ways a link can be a dead end, each worded and drawn as what it is:
                     one ran out, one never was, and one is this app failing rather than the link. -->
                <Empty.Root class="p-4">
                    <Empty.Header>
                        <Empty.Media variant="icon">
                            {#if state.type === "expired"}
                                <ClockCountdownIcon />
                            {:else if state.type === "missing"}
                                <LinkBreakIcon />
                            {:else}
                                <WarningCircleIcon />
                            {/if}
                        </Empty.Media>
                        <Empty.Title>{$_(`share.${state.type}.title`)}</Empty.Title>
                        <Empty.Description>{$_(`share.${state.type}.description`)}</Empty.Description>
                    </Empty.Header>

                    {#if state.type === "failed" && viewModel}
                        <Empty.Content>
                            <Button variant="secondary" onclick={() => viewModel.load()}>
                                {$_("share.failed.retry")}
                            </Button>
                        </Empty.Content>
                    {/if}
                </Empty.Root>
            {/if}
        </div>
    </main>
</div>
