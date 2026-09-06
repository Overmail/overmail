<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Badge} from "$lib/components/ui/badge";
    import * as Empty from "$lib/components/ui/empty";
    import * as Table from "$lib/components/ui/table";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {
        BrainIcon,
        CalendarBlankIcon,
        PencilSimpleIcon,
        PlusIcon,
        SparkleIcon,
        TrashIcon,
        WarningCircleIcon,
    } from "phosphor-svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {_, locale} from "svelte-i18n";

    const {knowledge: knowledgeRepository} = useRepositories();

    let entries: {type: "loading"} | {type: "loaded"; rows: KnowledgeEntry[]} | {type: "failed"} = $state({
        type: "loading",
    });

    async function load() {
        entries = {type: "loading"};
        try {
            entries = {type: "loaded", rows: await knowledgeRepository.list()};
        } catch {
            // Not an empty state, same as the mailboxes: "nothing could be read" and "the
            // assistant has not learned anything yet" are different answers.
            entries = {type: "failed"};
        }
    }

    $effect(() => {
        void load();
    });

    /**
     * The day an entry is about, in the reader's locale.
     *
     * The server sends `2026-04-01`, which `Date` would read as UTC midnight and a timezone behind
     * it would render as the day before -- the time makes it a local date instead.
     */
    const formatDay = (day: string) =>
        new Date(`${day}T00:00:00`).toLocaleDateString($locale ?? undefined, {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
        });
</script>

<!-- `min-w-0` for the same reason as in SettingsDialog: the table must be allowed to scroll. -->
<div class="flex min-w-0 flex-1 flex-col grow">
    {#if entries.type === "loading"}
        <div class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
            <Spinner class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.knowledge.list.loading")}</span>
        </div>
    {:else if entries.type === "failed"}
        <div class="text-destructive flex flex-row items-start gap-2 text-sm">
            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
            <span>{$_("settings.knowledge.list.failed")}</span>
        </div>
    {:else if entries.rows.length > 0}
        <div class="flex flex-col gap-4">
            <!-- Prepared, like the row actions below: writing knowledge from here is its own step. -->
            <Button class="w-fit" disabled>
                <PlusIcon />
                {$_("settings.knowledge.add")}
            </Button>

            <div class="overflow-hidden rounded-2xl border">
                <!--
                  No pinned column and no sideways scroll, unlike the mailboxes: a keyword is a
                  word and wraps, so every row fits the dialog and nothing runs off the edge.
                -->
                <Table.Root class="text-sm">
                    <Table.Header>
                        <Table.Row>
                            <Table.Head>{$_("settings.knowledge.list.columns.name")}</Table.Head>
                            <Table.Head>{$_("settings.knowledge.list.columns.description")}</Table.Head>
                            <Table.Head>{$_("settings.knowledge.list.columns.keywords")}</Table.Head>
                            <!--
                              The actions column. Empty on purpose: the buttons come in with the
                              pointer, and a header is never hovered.
                            -->
                            <Table.Head class="w-24"></Table.Head>
                        </Table.Row>
                    </Table.Header>
                    <Table.Body>
                        {#each entries.rows as entry (entry.id)}
                            <Table.Row class="group/row hover:bg-muted align-top">
                                <Table.Cell class="font-medium">
                                    <div class="flex flex-col gap-0.5">
                                        <div class="flex flex-row items-center gap-1.5">
                                            <span>{entry.name}</span>
                                            {#if entry.createdByAgent}
                                                <!--
                                                  Which of the two wrote it: the assistant picks
                                                  entries up while it sorts mail, and correcting
                                                  one of those is a different act than correcting
                                                  something the user typed.
                                                -->
                                                <Tooltip.Root>
                                                    <Tooltip.Trigger>
                                                        <SparkleIcon
                                                                class="text-muted-foreground size-3.5 shrink-0"
                                                                aria-label={$_("settings.knowledge.list.learnedByAgent")}
                                                        />
                                                    </Tooltip.Trigger>

                                                    <Tooltip.Content>
                                                        <span>{$_("settings.knowledge.list.learnedByAgent")}</span>
                                                    </Tooltip.Content>
                                                </Tooltip.Root>
                                            {/if}
                                        </div>
                                        {#if entry.relevantOn}
                                            <div class="text-muted-foreground flex flex-row items-center gap-1 text-xs">
                                                <CalendarBlankIcon class="size-3 shrink-0" />
                                                <span>{formatDay(entry.relevantOn)}</span>
                                            </div>
                                        {/if}
                                    </div>
                                </Table.Cell>
                                <Table.Cell class="text-muted-foreground max-w-md whitespace-normal">
                                    <!-- The whole entry can be a paragraph; the row shows its beginning. -->
                                    <span class="line-clamp-2">{entry.description}</span>
                                </Table.Cell>
                                <Table.Cell>
                                    {#if entry.keywords.length === 0}
                                        <span class="text-muted-foreground">
                                            {$_("settings.knowledge.list.noKeywords")}
                                        </span>
                                    {:else}
                                        <div class="flex max-w-xs flex-row flex-wrap gap-1">
                                            {#each entry.keywords as keyword (keyword)}
                                                <Badge variant="secondary">{keyword}</Badge>
                                            {/each}
                                        </div>
                                    {/if}
                                </Table.Cell>
                                <Table.Cell class="w-24">
                                    <!--
                                      Prepared, not wired: editing and forgetting an entry each
                                      open a dialog, and those come with the step that writes.
                                      Disabled rather than absent so the column is already the
                                      width it will keep.
                                    -->
                                    <div
                                            class="flex flex-row items-center justify-end gap-1 opacity-0
                                                   transition-opacity group-hover/row:opacity-100
                                                   has-focus-visible:opacity-100"
                                    >
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                disabled
                                                aria-label={$_("settings.knowledge.list.actions.edit")}
                                                title={$_("settings.knowledge.list.actions.edit")}
                                        >
                                            <PencilSimpleIcon />
                                        </Button>
                                        <Button
                                                variant="ghost"
                                                size="icon-sm"
                                                disabled
                                                class="text-destructive hover:text-destructive"
                                                aria-label={$_("settings.knowledge.list.actions.delete")}
                                                title={$_("settings.knowledge.list.actions.delete")}
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
                    <BrainIcon />
                </Empty.Media>
                <Empty.Title>{$_("settings.knowledge.empty.title")}</Empty.Title>
                <Empty.Description>{$_("settings.knowledge.empty.description")}</Empty.Description>
            </Empty.Header>

            <Empty.Content>
                <div class="flex flex-row items-center gap-2">
                    <Button class="w-fit" disabled>
                        <PlusIcon />
                        {$_("settings.knowledge.add")}
                    </Button>
                </div>
            </Empty.Content>
        </Empty.Root>
    {/if}
</div>
