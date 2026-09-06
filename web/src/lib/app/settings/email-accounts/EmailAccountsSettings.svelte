<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Badge} from "$lib/components/ui/badge";
    import * as Empty from "$lib/components/ui/empty";
    import * as Table from "$lib/components/ui/table";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {EnvelopeSimpleIcon, PencilSimpleIcon, PlusIcon, TrashIcon, WarningCircleIcon} from "phosphor-svelte";
    import NewEmailAccountDialog from "$lib/app/settings/email-accounts/new/NewEmailAccountDialog.svelte";
    import DeleteInboxDialog from "$lib/app/settings/email-accounts/DeleteInboxDialog.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {_} from "svelte-i18n";

    const {inboxes: inboxRepository} = useRepositories();

    let showNewEmailAccountDialog = $state(false);
    /** The mailbox the delete dialog is asking about; null while it is closed. */
    let inboxToDelete: Inbox | null = $state(null);

    let inboxes: {type: "loading"} | {type: "loaded"; rows: Inbox[]} | {type: "failed"} = $state({
        type: "loading",
    });

    async function load() {
        inboxes = {type: "loading"};
        try {
            inboxes = {type: "loaded", rows: await inboxRepository.list()};
        } catch {
            // Not an empty state: "nothing could be read" and "you have no mailboxes" are
            // different answers, and only one of them means the user has something to do.
            inboxes = {type: "failed"};
        }
    }

    $effect(() => {
        void load();
    });
</script>

<!-- `min-w-0` for the same reason as in SettingsDialog: the table must be allowed to scroll. -->
<div class="flex min-w-0 flex-1 flex-col grow">
    {#if inboxes.type === "loading"}
        <div class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
            <Spinner class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.emailAccounts.list.loading")}</span>
        </div>
    {:else if inboxes.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2 text-sm">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.emailAccounts.list.failed")}</span>
        </div>
    {:else if inboxes.rows.length > 0}
        <div class="flex flex-col gap-4">
            <Button
                    class="w-fit"
                    onclick={() => (showNewEmailAccountDialog = true)}
            >
                <PlusIcon />
                {$_("settings.emailAccounts.add")}
            </Button>
            
            <!-- `Table.Root` brings its own horizontally scrolling wrapper; this one only rounds it off. -->
            <div class="overflow-hidden rounded-2xl border">
                <Table.Root class="text-sm">
                    <Table.Header>
                        <Table.Row>
                            <!--
                              Pinned while the rest scrolls sideways: the username is what says
                              which row is which, and a row identified by nothing is not a row.
                            -->
                            <Table.Head class="bg-popover sticky left-0 z-20 pr-10">
                                {$_("settings.emailAccounts.list.columns.username")}
                                <!--
                                  No rule down the table: the pinned column ends in its own
                                  background running out, so what scrolls under it disappears
                                  instead of stopping at a line. The run-out sits inside the cell,
                                  in padding kept free for it -- hung outside, it depended on the
                                  neighbouring column being there and wide enough, which on a
                                  narrow viewport it is not.
                                -->
                                <div
                                        class="from-popover pointer-events-none absolute inset-y-0 right-0 w-8
                                               bg-linear-to-r to-transparent"
                                ></div>
                            </Table.Head>
                            <Table.Head>{$_("settings.emailAccounts.list.columns.server")}</Table.Head>
                            <Table.Head>{$_("settings.emailAccounts.list.columns.folders")}</Table.Head>
                            <!-- The actions column; its header is empty on purpose. -->
                            <Table.Head class="bg-popover sticky right-0 z-20 w-32 pl-10">
                                <div
                                        class="from-popover pointer-events-none absolute inset-y-0 left-0 w-8
                                               bg-linear-to-l to-transparent"
                                ></div>
                            </Table.Head>
                        </Table.Row>
                    </Table.Header>
                    <Table.Body>
                        {#each inboxes.rows as inbox (inbox.id)}
                            <Table.Row class="group/row hover:bg-muted">
                                <Table.Cell class="bg-popover group-hover/row:bg-muted sticky left-0 z-10 pr-10 font-medium">
                                    {inbox.username}
                                    <!-- The run-out follows the row: same colour hovered or not. -->
                                    <div
                                            class="from-popover group-hover/row:from-muted pointer-events-none
                                                   absolute inset-y-0 right-0 w-8 bg-linear-to-r to-transparent"
                                    ></div>
                                </Table.Cell>
                                <!--
                                  The port belongs with the host: two accounts on one provider
                                  differ by it, and a row showing only the host reads as a
                                  duplicate of the other.
                                -->
                                <Table.Cell class="text-muted-foreground whitespace-nowrap font-mono">
                                    {inbox.host}:{inbox.port}
                                </Table.Cell>
                                <Table.Cell>
                                    {#if inbox.folders.length === 0}
                                        <span class="text-muted-foreground">
                                            {$_("settings.emailAccounts.list.noFolders")}
                                        </span>
                                    {:else}
                                        <!--
                                          `flex-nowrap`: a folder list belongs on one line. It is
                                          what makes the table wider than the dialog, which is
                                          what the horizontal scroll is for.
                                        -->
                                        <!--
                                          `w-max` and `shrink-0`: without them the badges give way
                                          to the table's `w-full` and squash instead of pushing the
                                          row past the edge, so there would be nothing to scroll.
                                        -->
                                        <div class="flex w-max flex-row flex-nowrap gap-1">
                                            {#each inbox.folders as folder (folder)}
                                                <Badge variant="secondary" class="shrink-0 whitespace-nowrap">
                                                    {folder}
                                                </Badge>
                                            {/each}
                                        </div>
                                    {/if}
                                </Table.Cell>

                                <!--
                                  A column of its own rather than an overlay on the folders: the
                                  table scrolls sideways now, and an overlay would scroll away with
                                  the cell it sat in. Sticky, so the actions stay reachable wherever
                                  the folder list has been scrolled to.
                                -->
                                <Table.Cell class="bg-popover group-hover/row:bg-muted sticky right-0 z-10 w-32 pl-10">
                                    <div
                                            class="from-popover group-hover/row:from-muted pointer-events-none
                                                   absolute inset-y-0 left-0 w-8 bg-linear-to-l to-transparent"
                                    ></div>
                                    <div
                                            class="flex flex-row items-center justify-end gap-1 opacity-0
                                                   transition-opacity group-hover/row:opacity-100 focus-within:opacity-100"
                                    >
                                        <!-- TODO: no editing screen yet, see the note in the PR. -->
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                disabled
                                                aria-label={$_("settings.emailAccounts.list.actions.edit")}
                                                title={$_("settings.emailAccounts.list.actions.edit")}
                                        >
                                            <PencilSimpleIcon />
                                        </Button>
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                class="text-destructive hover:text-destructive"
                                                aria-label={$_("settings.emailAccounts.list.actions.delete")}
                                                title={$_("settings.emailAccounts.list.actions.delete")}
                                                onclick={() => (inboxToDelete = inbox)}
                                        >
                                            <TrashIcon />
                                        </Button>
                                    </div>
                                </Table.Cell>

                            </Table.Row>
                        {/each}
                    </Table.Body>
                </Table.Root>
            </div>
        </div>
    {:else}
        <Empty.Root class="h-full flex flex-col items-center justify-center border">
            <Empty.Header>
                <Empty.Media variant="icon">
                    <EnvelopeSimpleIcon />
                </Empty.Media>
                <Empty.Title>{$_("settings.emailAccounts.list.empty.title")}</Empty.Title>
                <Empty.Description>{$_("settings.emailAccounts.list.empty.description")}</Empty.Description>
            </Empty.Header>

            <Empty.Content>
                <div class="flex flex-row items-center gap-2">
                    <Button
                            class="w-fit"
                            onclick={() => (showNewEmailAccountDialog = true)}
                    >
                        <PlusIcon />
                        {$_("settings.emailAccounts.add")}
                    </Button>
                </div>
            </Empty.Content>
        </Empty.Root>
    {/if}
</div>

<!-- Neither dialog knows about this list, so each says what it did and this re-reads. -->
<NewEmailAccountDialog bind:open={showNewEmailAccountDialog} onCreated={load} />
<DeleteInboxDialog bind:inbox={inboxToDelete} onDeleted={load} />
