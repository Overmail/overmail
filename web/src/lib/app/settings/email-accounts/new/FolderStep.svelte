<script lang="ts">
    import * as Table from "$lib/components/ui/table";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import SetupStatusLine from "./SetupStatusLine.svelte";
    import type {FolderRow, NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte.ts";
    import AiProcessingControl from "./AiProcessingControl.svelte";
    import {CaretDownIcon, CaretRightIcon, WarningCircleIcon} from "phosphor-svelte";
    import {locale, _} from "svelte-i18n";

    let {viewModel}: {viewModel: NewEmailAccountViewModel} = $props();

    const scan = $derived(viewModel.folderScan);

    /** One indent per level of nesting; the chevron column keeps the names lined up under it. */
    const INDENT_PER_LEVEL = 18;

    function formatOldest(row: FolderRow): string {
        if (!row.counted) return $_("settings.emailAccounts.new.folders.pending");
        if (row.oldestMailAt === null) return $_("settings.emailAccounts.new.folders.unknown");
        return new Date(row.oldestMailAt).toLocaleDateString($locale ?? undefined, {
            year: "numeric",
            month: "short",
            day: "numeric",
        });
    }

    function formatCount(row: FolderRow): string {
        if (!row.counted) return $_("settings.emailAccounts.new.folders.pending");
        if (row.mailCount === null) return $_("settings.emailAccounts.new.folders.unknown");
        return row.mailCount.toLocaleString($locale ?? undefined);
    }

</script>

<div class="flex flex-col gap-3">
    <!--
      The tree lands in one piece and the numbers arrive per folder, so this says which of the two
      is happening -- on a real mailbox the counting is seconds of work.
    -->
    <div aria-live="polite" class="min-h-5">
        {#if scan.type === "listing"}
            <SetupStatusLine
                    icon={Spinner}
                    message={$_("settings.emailAccounts.new.folders.listing")}
                    tone="text-muted-foreground"
            />
        {:else if scan.type === "counting"}
            <SetupStatusLine
                    icon={Spinner}
                    message={$_("settings.emailAccounts.new.folders.counting", {
                        values: {counted: scan.counted, total: scan.total},
                    })}
                    tone="text-muted-foreground"
            />
        {:else if scan.type === "failed"}
            <SetupStatusLine
                    icon={WarningCircleIcon}
                    message={$_("settings.emailAccounts.new.folders.failed")}
                    tone="text-destructive"
            />
        {/if}
    </div>

    {#if viewModel.folders.length > 0}
        <div class="max-h-80 overflow-y-auto rounded-2xl border">
            <Table.Root class="table-fixed text-sm">
                <Table.Header>
                    <Table.Row>
                        <Table.Head class="w-10"></Table.Head>
                        <Table.Head>{$_("settings.emailAccounts.new.folders.columns.folder")}</Table.Head>
                        <Table.Head class="w-20 text-right">
                            {$_("settings.emailAccounts.new.folders.columns.mails")}
                        </Table.Head>
                        <Table.Head class="w-28">
                            {$_("settings.emailAccounts.new.folders.columns.oldest")}
                        </Table.Head>
                        <!--
                          Fixed, and only as wide as the control: it carries its own value, so
                          there is no field beside it to reserve room for. A column that grew when
                          one row switched to a date would shift every other row sideways.
                        -->
                        <Table.Head class="w-56">
                            {$_("settings.emailAccounts.new.folders.columns.ai")}
                        </Table.Head>
                    </Table.Row>
                </Table.Header>
                <Table.Body>
                    {#each viewModel.visibleFolders as row (row.fullName)}
                        <Table.Row>
                            <Table.Cell>
                                <Checkbox
                                        checked={row.enabled}
                                        onCheckedChange={() => viewModel.toggleFolder(row.fullName)}
                                        aria-label={row.fullName}
                                />
                            </Table.Cell>

                            <Table.Cell>
                                <div
                                        class="flex flex-row items-center gap-1"
                                        style="padding-left: {row.depth * INDENT_PER_LEVEL}px"
                                >
                                    {#if row.hasChildren}
                                        <button
                                                type="button"
                                                class="text-muted-foreground hover:text-foreground -ml-1 rounded p-0.5"
                                                onclick={() => viewModel.toggleCollapsed(row.fullName)}
                                                aria-expanded={!viewModel.collapsed.includes(row.fullName)}
                                                aria-label={row.name}
                                        >
                                            {#if viewModel.collapsed.includes(row.fullName)}
                                                <CaretRightIcon class="size-3.5" />
                                            {:else}
                                                <CaretDownIcon class="size-3.5" />
                                            {/if}
                                        </button>
                                    {:else}
                                        <!-- Keeps a leaf's name in the same column as a parent's. -->
                                        <span class="size-3.5 shrink-0"></span>
                                    {/if}
                                    <span class="truncate">{row.name}</span>
                                </div>
                            </Table.Cell>

                            <Table.Cell class="text-right tabular-nums">{formatCount(row)}</Table.Cell>
                            <Table.Cell class="whitespace-nowrap">{formatOldest(row)}</Table.Cell>

                            <Table.Cell>
                                <AiProcessingControl
                                        mode={row.aiProcessing}
                                        disabled={!row.enabled}
                                        onChange={(mode) => viewModel.setAiProcessing(row.fullName, mode)}
                                />
                            </Table.Cell>
                        </Table.Row>
                    {/each}
                </Table.Body>
            </Table.Root>
        </div>
    {:else if scan.type === "done"}
        <p class="text-muted-foreground text-sm">{$_("settings.emailAccounts.new.folders.empty")}</p>
    {/if}
</div>
