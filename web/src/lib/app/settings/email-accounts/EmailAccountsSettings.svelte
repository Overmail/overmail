<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Badge} from "$lib/components/ui/badge";
    import * as Empty from "$lib/components/ui/empty";
    import * as Table from "$lib/components/ui/table";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {EnvelopeSimpleIcon, PlusIcon, WarningCircleIcon} from "phosphor-svelte";
    import NewEmailAccountDialog from "$lib/app/settings/email-accounts/new/NewEmailAccountDialog.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {_} from "svelte-i18n";

    const {inboxes: inboxRepository} = useRepositories();

    let showNewEmailAccountDialog = $state(false);

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

<div class="flex flex-col flex-1 grow">
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
            
            <div class="overflow-hidden rounded-2xl border">
                <Table.Root class="text-sm">
                    <Table.Header>
                        <Table.Row>
                            <Table.Head>{$_("settings.emailAccounts.list.columns.username")}</Table.Head>
                            <Table.Head>{$_("settings.emailAccounts.list.columns.server")}</Table.Head>
                            <Table.Head>{$_("settings.emailAccounts.list.columns.folders")}</Table.Head>
                        </Table.Row>
                    </Table.Header>
                    <Table.Body>
                        {#each inboxes.rows as inbox (inbox.id)}
                            <Table.Row>
                                <Table.Cell class="font-medium">{inbox.username}</Table.Cell>
                                <!--
                                  The port belongs with the host: two accounts on one provider
                                  differ by it, and a row showing only the host reads as a
                                  duplicate of the other.
                                -->
                                <Table.Cell class="text-muted-foreground whitespace-nowrap">
                                    {inbox.host}:{inbox.port}
                                </Table.Cell>
                                <Table.Cell>
                                    {#if inbox.folders.length === 0}
                                        <span class="text-muted-foreground">
                                            {$_("settings.emailAccounts.list.noFolders")}
                                        </span>
                                    {:else}
                                        <div class="flex flex-row flex-wrap gap-1">
                                            {#each inbox.folders as folder (folder)}
                                                <Badge variant="secondary">{folder}</Badge>
                                            {/each}
                                        </div>
                                    {/if}
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

<!-- The dialog does not know about this list, so it says it created something and this re-reads. -->
<NewEmailAccountDialog bind:open={showNewEmailAccountDialog} onCreated={load} />
