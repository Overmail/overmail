<!--
    The links this mail is already out under.

    Expired ones stay in the list: a link that stopped working is one the owner may still want to
    see, and it is the only place they can take it back for good.
-->
<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Spinner} from "$lib/components/ui/spinner";
    import {
        CheckIcon,
        CopyIcon,
        LockSimpleIcon,
        PencilSimpleIcon,
        TagIcon,
        TrashIcon,
        WarningCircleIcon,
    } from "phosphor-svelte";
    import {_, locale} from "svelte-i18n";
    import type {Share} from "$lib/repository/ShareRepository";
    import type {ShareDialogViewModel} from "$lib/app/mails/detail_panel/share/ShareDialogViewModel.svelte";
    import {shareUrl, subjectFor} from "$lib/app/mails/detail_panel/share/sharePath";

    let {
        viewModel,
        subject,
        onCopyFailed,
    }: {
        viewModel: ShareDialogViewModel,
        /** What the link says it is about, where the share lets a visitor see that anyway. */
        subject?: string | null,
        /** The clipboard is the browser's to refuse, and the message for it sits in the dialog. */
        onCopyFailed?: (failed: boolean) => void,
    } = $props();

    /** Read once per render: what counts as expired only has to be right when the list is drawn. */
    const nowSeconds = Math.floor(Date.now() / 1000);

    function validity(share: Share): string {
        if (share.validUntil === null) return $_("mails.share.list.noExpiry");
        if (share.validUntil <= nowSeconds) return $_("mails.share.list.expired");

        const day = new Date(share.validUntil * 1000).toLocaleDateString($locale ?? undefined, {
            year: "numeric",
            month: "short",
            day: "numeric",
        });
        return $_("mails.share.list.expiresAt", {values: {date: day}});
    }

    async function copy(share: Share) {
        const url = shareUrl(share.id, location.origin, subjectFor(share, subject));
        onCopyFailed?.(!(await viewModel.copy(share, url)));
    }
</script>

<div class="flex flex-col gap-2">
    <h3 class="text-sm font-medium">{$_("mails.share.list.title")}</h3>

    {#if viewModel.listState.type === "loading"}
        <div class="text-muted-foreground flex flex-row items-center gap-2 text-sm">
            <Spinner class="size-4 shrink-0" />
            <span>{$_("mails.share.list.loading")}</span>
        </div>
    {:else if viewModel.listState.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2 text-sm">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{$_("mails.share.list.failed")}</span>
        </div>
    {:else if viewModel.shares.length === 0}
        <p class="text-muted-foreground text-sm">{$_("mails.share.list.empty")}</p>
    {:else}
        <ul class="flex flex-col gap-1">
            {#each viewModel.shares as share (share.id)}
                <li
                        class="flex flex-row items-center gap-2 rounded-md px-2 py-1.5"
                        class:bg-muted={viewModel.editing?.id === share.id}
                >
                    <div class="flex min-w-0 flex-col">
                        <span class="truncate text-sm" class:text-muted-foreground={!share.shareName}>
                            {share.shareName ?? $_("mails.share.list.unnamed")}
                        </span>

                        <span class="text-muted-foreground flex flex-row items-center gap-2 text-xs">
                            <span class="truncate">{validity(share)}</span>

                            {#if share.hasPassword}
                                <LockSimpleIcon class="size-3 shrink-0" aria-label={$_("mails.share.list.password")} />
                            {/if}
                            {#if share.includeLabels}
                                <TagIcon class="size-3 shrink-0" aria-label={$_("mails.share.list.labels")} />
                            {/if}
                        </span>
                    </div>

                    <div class="ms-auto flex flex-row items-center gap-1">
                        <Button
                                variant="ghost"
                                size="icon"
                                title={$_("mails.share.list.copy")}
                                aria-label={$_("mails.share.list.copy")}
                                onclick={() => void copy(share)}
                        >
                            {#if viewModel.copied === share.id}
                                <CheckIcon />
                            {:else}
                                <CopyIcon />
                            {/if}
                        </Button>

                        <Button
                                variant="ghost"
                                size="icon"
                                title={$_("mails.share.list.edit")}
                                aria-label={$_("mails.share.list.edit")}
                                onclick={() => viewModel.startEdit(share)}
                        >
                            <PencilSimpleIcon />
                        </Button>

                        <Button
                                variant="ghost"
                                size="icon"
                                title={$_("mails.share.list.delete")}
                                aria-label={$_("mails.share.list.delete")}
                                onclick={() => viewModel.askToDelete(share)}
                        >
                            <TrashIcon />
                        </Button>
                    </div>
                </li>
            {/each}
        </ul>
    {/if}
</div>
