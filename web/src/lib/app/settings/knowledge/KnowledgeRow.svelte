<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {Button} from "$lib/components/ui/button";
    import * as Table from "$lib/components/ui/table";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {CalendarBlankIcon, PencilSimpleIcon, SparkleIcon, TrashIcon} from "phosphor-svelte";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {PINNED_LEFT_EDGE, PINNED_LEFT_EDGE_ON} from "$lib/app/settings/knowledge/pinnedColumn";
    import {cn} from "$lib/utils";
    import {_, locale} from "svelte-i18n";

    let {
        ref = $bindable(null),
        index = undefined,
        entry,
        pinned = false,
        onedit,
        ondelete,
        onchanged,
    }: {
        /** The row element, so a virtualized table can measure what this row actually takes. */
        ref?: HTMLTableRowElement | null,
        /** Its place in the list; a virtualizer reads it back off the element as `data-index`. */
        index?: number,
        entry: KnowledgeEntry,
        /** Whether anything is hidden past the left edge, which is what the name column marks. */
        pinned?: boolean,
        onedit: (entry: KnowledgeEntry) => void,
        ondelete: (entry: KnowledgeEntry) => void,
        /**
         * An entry this row wrote itself, as the server answered it. The list patches its copy
         * rather than re-reading, so the rest of the table stays where it is.
         */
        onchanged: (entry: KnowledgeEntry) => void,
    } = $props();

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

<Table.Row bind:ref data-index={index} class="group/row hover:bg-muted align-top">
    <!--
      Pinned while the rest scrolls sideways, like the username in the mailbox table: the name is
      what says which row is which, and a row identified by nothing is not a row.
    -->
    <Table.Cell
            class={cn(
                "bg-popover group-hover/row:bg-muted sticky left-0 z-10 font-medium",
                "transition-[background-color,box-shadow]",
                PINNED_LEFT_EDGE,
                pinned && PINNED_LEFT_EDGE_ON,
            )}
    >
        <div class="flex flex-col gap-0.5">
            <div class="flex flex-row items-center gap-1.5">
                <span>{entry.name}</span>
                {#if entry.createdByAgent}
                    <!--
                      Which of the two wrote it: the assistant picks entries up while it sorts
                      mail, and correcting one of those is a different act than correcting
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

    <Table.Cell class="text-muted-foreground min-w-64 whitespace-normal">
        <!-- The whole entry can be a paragraph; the row shows its beginning. -->
        <span class="line-clamp-2">{entry.description}</span>
    </Table.Cell>

    <Table.Cell>
        {#if entry.keywords.length === 0}
            <span class="text-muted-foreground">{$_("settings.knowledge.list.noKeywords")}</span>
        {:else}
            <div class="flex max-w-xs flex-row flex-wrap gap-1">
                {#each entry.keywords as keyword (keyword)}
                    <Badge variant="secondary">{keyword}</Badge>
                {/each}
            </div>
        {/if}
    </Table.Cell>

    <!--
      Sticky like the name, and for the same reason: the table scrolls sideways, and an action
      that has scrolled out of reach is one nobody uses. The backdrop comes in with the buttons --
      until the row is hovered the keywords may run the full width. See the mailbox table, which
      this follows.
    -->
    <Table.Cell class="sticky right-0 z-10 w-24 pl-10">
        <div
                class="to-popover group-hover/row:to-muted pointer-events-none absolute inset-y-0 left-0 w-8
                       bg-linear-to-r from-transparent opacity-0 transition group-hover/row:opacity-100"
        ></div>
        <div
                class="bg-popover group-hover/row:bg-muted pointer-events-none absolute inset-y-0 right-0 left-8
                       opacity-0 transition group-hover/row:opacity-100"
        ></div>
        <div
                class="relative flex flex-row items-center justify-end gap-1 opacity-0 transition-opacity
                       group-hover/row:opacity-100 has-focus-visible:opacity-100"
        >
            <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={$_("settings.knowledge.list.actions.edit")}
                    title={$_("settings.knowledge.list.actions.edit")}
                    onclick={() => onedit(entry)}
            >
                <PencilSimpleIcon />
            </Button>
            <Button
                    variant="ghost"
                    size="icon-sm"
                    class="text-destructive hover:text-destructive"
                    aria-label={$_("settings.knowledge.list.actions.delete")}
                    title={$_("settings.knowledge.list.actions.delete")}
                    onclick={() => ondelete(entry)}
            >
                <TrashIcon />
            </Button>
        </div>
    </Table.Cell>
</Table.Row>
