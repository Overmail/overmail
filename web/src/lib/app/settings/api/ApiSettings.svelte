<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import * as Empty from "$lib/components/ui/empty";
    import * as Table from "$lib/components/ui/table";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {ArrowSquareOutIcon, KeyIcon, PlusIcon, TrashIcon, WarningCircleIcon} from "phosphor-svelte";
    import RelativeTime from "$lib/components/time/RelativeTime.svelte";
    import NewApiKeyDialog from "$lib/app/settings/api/NewApiKeyDialog.svelte";
    import DeleteApiKeyDialog from "$lib/app/settings/api/DeleteApiKeyDialog.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {API_DOCUMENTATION_URL, type ApiKey} from "$lib/repository/ApiKeysRepository";
    import {_, locale} from "svelte-i18n";

    const {apiKeys: apiKeysRepository} = useRepositories();

    let showNewApiKeyDialog = $state(false);
    /** The key the delete dialog is asking about; null while it is closed. */
    let apiKeyToDelete: ApiKey | null = $state(null);

    let apiKeys: {type: "loading"} | {type: "loaded"; rows: ApiKey[]} | {type: "failed"} = $state({
        type: "loading",
    });

    async function load() {
        apiKeys = {type: "loading"};
        try {
            apiKeys = {type: "loaded", rows: await apiKeysRepository.list()};
        } catch {
            // Not an empty state: "nothing could be read" and "you have no keys" are different answers.
            apiKeys = {type: "failed"};
        }
    }

    $effect(() => {
        void load();
    });

    function exact(date: Date): string {
        return date.toLocaleString($locale ?? undefined, {dateStyle: "medium", timeStyle: "short"});
    }
</script>

<div class="flex min-w-0 flex-1 flex-col gap-4 grow">
    <!-- What a key is for comes first, whether there are keys or not. -->
    <div class="flex flex-col gap-1 text-sm">
        <p class="text-muted-foreground">{$_("settings.api.intro")}</p>
        <!-- A full page load: Swagger UI is served by the server, not by this app. -->
        <a
                class="text-primary inline-flex w-fit flex-row items-center gap-1 hover:underline"
                href={API_DOCUMENTATION_URL}
                target="_blank"
                rel="noopener"
                data-sveltekit-reload
        >
            {$_("settings.api.documentation")}
            <ArrowSquareOutIcon class="size-3.5" />
        </a>
    </div>

    {#if apiKeys.type === "loading"}
        <div class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
            <Spinner class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.api.list.loading")}</span>
        </div>
    {:else if apiKeys.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2 text-sm">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.api.list.failed")}</span>
        </div>
    {:else if apiKeys.rows.length > 0}
        <Button class="w-fit" onclick={() => (showNewApiKeyDialog = true)}>
            <PlusIcon />
            {$_("settings.api.add")}
        </Button>

        <div class="overflow-hidden rounded-2xl border">
            <Table.Root class="text-sm">
                <Table.Header>
                    <Table.Row>
                        <Table.Head>{$_("settings.api.list.columns.name")}</Table.Head>
                        <Table.Head>{$_("settings.api.list.columns.key")}</Table.Head>
                        <Table.Head>{$_("settings.api.list.columns.created")}</Table.Head>
                        <Table.Head>{$_("settings.api.list.columns.lastUsed")}</Table.Head>
                        <Table.Head class="w-12"></Table.Head>
                    </Table.Row>
                </Table.Header>
                <Table.Body>
                    {#each apiKeys.rows as apiKey (apiKey.id)}
                        <Table.Row class="group/row">
                            <Table.Cell class="font-medium">{apiKey.name}</Table.Cell>
                            <!-- Only the start of it: the key itself was shown once and is gone. -->
                            <Table.Cell class="text-muted-foreground font-mono text-xs whitespace-nowrap">
                                {apiKey.prefix}…
                            </Table.Cell>
                            <Table.Cell class="text-muted-foreground whitespace-nowrap">
                                <RelativeTime date={apiKey.createdAt} title={exact(apiKey.createdAt)} />
                            </Table.Cell>
                            <Table.Cell class="text-muted-foreground whitespace-nowrap">
                                {#if apiKey.lastUsedAt}
                                    <RelativeTime date={apiKey.lastUsedAt} title={exact(apiKey.lastUsedAt)} />
                                {:else}
                                    {$_("settings.api.list.neverUsed")}
                                {/if}
                            </Table.Cell>
                            <Table.Cell class="text-right">
                                <Button
                                        variant="ghost"
                                        size="icon-sm"
                                        class="text-destructive hover:text-destructive opacity-0 transition-opacity
                                               group-hover/row:opacity-100 focus-visible:opacity-100"
                                        aria-label={$_("settings.api.list.actions.delete")}
                                        title={$_("settings.api.list.actions.delete")}
                                        onclick={() => (apiKeyToDelete = apiKey)}
                                >
                                    <TrashIcon />
                                </Button>
                            </Table.Cell>
                        </Table.Row>
                    {/each}
                </Table.Body>
            </Table.Root>
        </div>
    {:else}
        <Empty.Root class="flex flex-1 flex-col items-center justify-center border">
            <Empty.Header>
                <Empty.Media variant="icon">
                    <KeyIcon />
                </Empty.Media>
                <Empty.Title>{$_("settings.api.list.empty.title")}</Empty.Title>
                <Empty.Description>{$_("settings.api.list.empty.description")}</Empty.Description>
            </Empty.Header>

            <Empty.Content>
                <Button class="w-fit" onclick={() => (showNewApiKeyDialog = true)}>
                    <PlusIcon />
                    {$_("settings.api.add")}
                </Button>
            </Empty.Content>
        </Empty.Root>
    {/if}
</div>

<!-- The new key goes to the top of the list, so this re-reads rather than patches. -->
<NewApiKeyDialog bind:open={showNewApiKeyDialog} onCreated={load} />
<DeleteApiKeyDialog bind:apiKey={apiKeyToDelete} onDeleted={load} />
