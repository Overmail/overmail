<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import * as Table from "$lib/components/ui/table";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {CalendarBlankIcon, PencilSimpleIcon, SparkleIcon, TrashIcon} from "phosphor-svelte";
    import type {KnowledgeEntry} from "$lib/repository/KnowledgeRepository";
    import {PINNED_LEFT_EDGE, PINNED_LEFT_EDGE_ON} from "$lib/app/settings/knowledge/pinnedColumn";
    import InlineKeywords from "$lib/app/settings/knowledge/InlineKeywords.svelte";
    import InlineTextEdit from "$lib/app/settings/knowledge/InlineTextEdit.svelte";
    import {
        InlineEditing,
        withKeyword,
        withoutKeyword,
    } from "$lib/app/settings/knowledge/inlineEditing.svelte.ts";
    import {useRepositories} from "$lib/repository/repositories";
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

    const {knowledge} = useRepositories();

    /**
     * Correcting a spelling or adding one keyword is most of what happens here, and the dialog is
     * a lot of ceremony for it. All of this is state of *this* row: the table is virtualized, so
     * a row that scrolls out is unmounted mid-edit, and nothing may outlive it.
     */
    const editing = new InlineEditing(knowledge);

    /** Which cell is open for typing. The keyword field keeps its own, in [InlineKeywords]. */
    let openField: "name" | "description" | null = $state(null);

    // A virtualizer may hand this component the next entry rather than building a new row, and an
    // editor left standing would then be writing into somebody else's entry. Only a different
    // entry closes it: the same one arriving again is this row's own save coming back.
    // svelte-ignore state_referenced_locally
    let editedId = entry.id;
    $effect(() => {
        if (entry.id === editedId) return;
        editedId = entry.id;
        openField = null;
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

    /**
     * Double-click is the mouse way in, so Enter and F2 on the focused text are the keyboard one
     * -- a cell nobody can reach without a mouse is a cell half the users do not have.
     */
    function startsEditing(event: KeyboardEvent): boolean {
        return event.key === "Enter" || event.key === "F2";
    }

    async function saveName(name: string) {
        const trimmed = name.trim();
        // An entry without a name is not an entry, and neither is one saved as what it already
        // says. Escape is how the user leaves it as it was.
        if (trimmed.length === 0) return;
        if (trimmed === entry.name) {
            openField = null;
            return;
        }

        const saved = await editing.save(entry, "name", {name: trimmed});
        if (!saved) return;

        onchanged(saved);
        openField = null;
    }

    async function saveDescription(description: string) {
        const trimmed = description.trim();
        if (trimmed.length === 0) return;
        if (trimmed === entry.description) {
            openField = null;
            return;
        }

        const saved = await editing.save(entry, "description", {description: trimmed});
        if (!saved) return;

        onchanged(saved);
        openField = null;
    }

    /** Answers whether the field may clear itself: it may, unless the server refused the write. */
    async function addKeyword(raw: string): Promise<boolean> {
        const keywords = withKeyword(entry.keywords, raw);
        // Nothing to write -- the word is already a chip, or the entry is at its limit. The field
        // still clears, because what was typed is what the row already says.
        if (!keywords) return true;

        const saved = await editing.save(entry, "keywords", {keywords});
        if (saved) onchanged(saved);
        return saved !== null;
    }

    async function removeKeyword(keyword: string) {
        const saved = await editing.save(entry, "keywords", {
            keywords: withoutKeyword(entry.keywords, keyword),
        });
        if (saved) onchanged(saved);
    }
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
            {#if openField === "name"}
                <InlineTextEdit
                        value={entry.name}
                        label={$_("settings.knowledge.inline.editName")}
                        saving={editing.savingIn("name")}
                        failure={editing.failureIn("name")}
                        onsave={(name) => void saveName(name)}
                        oncancel={() => (openField = null)}
                        ontype={() => editing.clearFailure("name")}
                />
            {:else}
                <div class="flex flex-row items-center gap-1.5">
                    <!--
                      The handler sits on the name and not on the cell, so double-clicking the
                      date under it or the marker next to it does not open an editor.
                    -->
                    <!-- svelte-ignore a11y_no_noninteractive_element_to_interactive_role -->
                    <span
                            role="button"
                            tabindex="0"
                            class="focus-visible:ring-ring/50 rounded-sm outline-none focus-visible:ring-2"
                            title={$_("settings.knowledge.inline.hint")}
                            ondblclick={() => (openField = "name")}
                            onkeydown={(event) => {
                                if (!startsEditing(event)) return;
                                event.preventDefault();
                                openField = "name";
                            }}
                    >{entry.name}</span>
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
            {/if}
            {#if entry.relevantOn}
                <div class="text-muted-foreground flex flex-row items-center gap-1 text-xs">
                    <CalendarBlankIcon class="size-3 shrink-0" />
                    <span>{formatDay(entry.relevantOn)}</span>
                </div>
            {/if}
        </div>
    </Table.Cell>

    <Table.Cell class="text-muted-foreground min-w-64 whitespace-normal">
        {#if openField === "description"}
            <InlineTextEdit
                    multiline
                    value={entry.description}
                    label={$_("settings.knowledge.inline.editText")}
                    saving={editing.savingIn("description")}
                    failure={editing.failureIn("description")}
                    onsave={(description) => void saveDescription(description)}
                    oncancel={() => (openField = null)}
                    ontype={() => editing.clearFailure("description")}
            />
        {:else}
            <!-- The whole entry can be a paragraph; the row shows its beginning. -->
            <!-- svelte-ignore a11y_no_noninteractive_element_to_interactive_role -->
            <span
                    role="button"
                    tabindex="0"
                    class="focus-visible:ring-ring/50 line-clamp-2 rounded-sm outline-none focus-visible:ring-2"
                    title={$_("settings.knowledge.inline.hint")}
                    ondblclick={() => (openField = "description")}
                    onkeydown={(event) => {
                        if (!startsEditing(event)) return;
                        event.preventDefault();
                        openField = "description";
                    }}
            >{entry.description}</span>
        {/if}
    </Table.Cell>

    <Table.Cell>
        <InlineKeywords
                keywords={entry.keywords}
                saving={editing.savingIn("keywords")}
                failure={editing.failureIn("keywords")}
                onadd={addKeyword}
                onremove={(keyword) => void removeKeyword(keyword)}
        />
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
