<!--
    How a listing is cut up: which categories group it, in which order, which way round each of
    them sorts, and what orders the mails inside the deepest one.

    A chip like the filters beside it, with the settings in its menu. It says "Gruppierung" and
    nothing else -- what it is set to is four categories with a direction each, and no line of text
    says that better than the menu itself does.

    What goes in and out is the server's own shape (`ViewSettings`): the categories outermost
    first, and the sorting of the mails under them. The order of the *unused* ones is this
    component's own -- it is where a category waits, not something a view is.
-->
<script lang="ts" module>
    import type {Component} from "svelte";
    import type {ViewGroupingKind} from "$lib/repository/ViewSocket";

    /** One category the listing can be cut by, as the menu shows it. */
    type GroupCategory = {
        kind: ViewGroupingKind;
        name: string;
        icon: Component;
        sort: {
            /** Whether this category's own order can be turned around. */
            reversible?: boolean;
            /** The whole sentence for the tooltip, per direction. */
            label: string;
            label_reversed?: string;
        };
    };
</script>

<script lang="ts">
    import {
        ArchiveIcon,
        CalendarDotIcon,
        CalendarDotsIcon,
        CalendarIcon,
        CalendarStarIcon,
        CaretDownIcon,
        DotsSixVerticalIcon,
        EyeglassesIcon,
        PersonSimpleIcon,
        SortAscendingIcon,
        SortDescendingIcon,
        TreeStructureIcon,
        UsersIcon,
    } from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {DndReorderElement, DndReorderHandle, DndReorderZone} from "$lib/components/dnd";
    import {DndReorder} from "$lib/hooks/dnd-reorder.svelte";
    import {moveTo} from "$lib/hooks/dnd-reorder";
    import {filterChip} from "$lib/app/filters/chip";
    import type {ViewGrouping, ViewSorting} from "$lib/repository/ViewSocket";
    import {cn} from "$lib/utils";

    let {
        groupings = $bindable([]),
        sorting = $bindable({kind: "date", reversed: false}),
        onGroupingsChanged,
        onSortingChanged,
        class: className,
    }: {
        /** The categories the listing is cut by, outermost first. Empty is an ungrouped listing. */
        groupings?: ViewGrouping[];
        /** What orders the mails inside the deepest category. */
        sorting?: ViewSorting;
        /** The categories after a change, for a caller that saves rather than only reads. */
        onGroupingsChanged?: (groupings: ViewGrouping[]) => void;
        onSortingChanged?: (sorting: ViewSorting) => void;
        /** Where the chip sits; it brings no margin of its own. */
        class?: string;
    } = $props();

    /** The same pill the filters beside it are; this one is a single button, so both slots go
     *  on it, and it is never in the "set" colour -- see the trigger below. */
    const chip = filterChip();

    /** How deep a listing may be cut. Past that the groups hold a handful of mails each. */
    const MAX_ACTIVE = 4;

    const CATEGORIES: GroupCategory[] = [
        {kind: "date_smart", name: "Datum (intelligent)", icon: CalendarStarIcon, sort: {reversible: true, label: "Nach Datum, neueste zuerst", label_reversed: "Nach Datum, älteste zuerst"}},
        {kind: "read", name: "Gelesen", icon: EyeglassesIcon, sort: {reversible: true, label: "Gelesene zuerst", label_reversed: "Ungelesene zuerst"}},
        {kind: "month", name: "Monat", icon: CalendarDotsIcon, sort: {reversible: true, label: "Nach Monat, neueste zuerst", label_reversed: "Nach Monat, älteste zuerst"}},
        {kind: "year", name: "Jahr", icon: CalendarDotIcon, sort: {reversible: true, label: "Nach Jahr, neueste zuerst", label_reversed: "Nach Jahr, älteste zuerst"}},
        {kind: "day", name: "Tag", icon: CalendarIcon, sort: {reversible: true, label: "Nach Tag, neueste zuerst", label_reversed: "Nach Tag, älteste zuerst"}},
        {kind: "sender", name: "Absender", icon: PersonSimpleIcon, sort: {reversible: true, label: "Absender, A–Z", label_reversed: "Absender, Z–A"}},
        {kind: "imap_account", name: "E-Mail-Konto", icon: UsersIcon, sort: {reversible: true, label: "E-Mail-Konto, A–Z", label_reversed: "E-Mail-Konto, Z–A"}},
        {kind: "archived", name: "Archiviert", icon: ArchiveIcon, sort: {reversible: true, label: "Aktive zuerst", label_reversed: "Archivierte zuerst"}},
    ];

    // `name` is what the row reads; `label`/`label_reversed` are the full tooltip sentences for
    // the two directions, same rule as a category's `sort`.
    const MAIL_SORTS: {kind: ViewSorting["kind"]; name: string; label: string; label_reversed: string}[] = [
        {kind: "date", name: "Datum", label: "Nach Datum, neueste zuerst", label_reversed: "Nach Datum, älteste zuerst"},
        {kind: "sender", name: "Absender", label: "Absender, A–Z", label_reversed: "Absender, Z–A"},
        {kind: "subject", name: "Betreff", label: "Betreff, A–Z", label_reversed: "Betreff, Z–A"},
    ];

    const byKind = new Map(CATEGORIES.map((category) => [category.kind, category]));

    /**
     * Where the categories that are not grouping wait, in the order they are offered.
     *
     * This one's own: a view says which categories cut it, not where the rest sit. Seeded from
     * what is not in [groupings] and then left alone -- a category dragged out goes to the place
     * it was dropped, and that place is worth keeping while the menu is open.
     */
    let inactiveKinds: ViewGroupingKind[] = $state(
        CATEGORIES.map((category) => category.kind).filter(
            (kind) => !groupings.some((grouping) => grouping.kind === kind)
        )
    );

    /**
     * Which way round each category sorts, including the ones that are not grouping right now:
     * dragging a category out and back in should not forget which way it was turned. The ones in
     * [groupings] are what counts; this is the memory behind it.
     */
    let reversed: Partial<Record<ViewGroupingKind, boolean>> = $state(
        Object.fromEntries(groupings.map((grouping) => [grouping.kind, grouping.reversed]))
    );

    const dnd = new DndReorder({
        zones: () => ({
            active: groupings.map((grouping) => byKind.get(grouping.kind)!).filter(Boolean),
            inactive: inactiveKinds.map((kind) => byKind.get(kind)!).filter(Boolean),
        }),
        id: (category) => category.kind,
        /**
         * The active group holds MAX_ACTIVE and no more, and a drag into a full one is not
         * refused: whoever is pushed past the end moves over to the inactive ones, which is what
         * makes the boundary feel like a shelf rather than a wall.
         */
        applyMove: (order, move) => {
            const next = moveTo(order, move);
            if (next.active.length <= MAX_ACTIVE) return next;

            // The dragged one keeps the place the cursor gave it; the one beside it goes.
            const evicted = next.active.at(-1) === move.id ? next.active.at(-2) : next.active.at(-1);
            if (evicted === undefined) return null;

            return {
                active: next.active.filter((kind) => kind !== evicted),
                inactive: [evicted, ...next.inactive],
            };
        },
        onDrop: ({order}) => {
            inactiveKinds = order.inactive as ViewGroupingKind[];
            setGroupings(
                (order.active as ViewGroupingKind[]).map((kind) => ({
                    kind,
                    reversed: reversed[kind] ?? false,
                }))
            );
        },
    });

    /** The deepest active category, which is the only one that holds mails directly. */
    const deepest = $derived(dnd.zones.active.at(-1));

    function setGroupings(next: ViewGrouping[]) {
        groupings = next;
        onGroupingsChanged?.(next);
    }

    function sortLabel(category: GroupCategory) {
        return reversed[category.kind] ? category.sort.label_reversed ?? category.sort.label : category.sort.label;
    }

    function toggleSort(category: GroupCategory) {
        if (!category.sort.reversible) return;

        const next = !reversed[category.kind];
        reversed[category.kind] = next;
        setGroupings(
            groupings.map((grouping) =>
                grouping.kind === category.kind ? {...grouping, reversed: next} : grouping
            )
        );
    }

    // Reversing is not an option of its own: picking the already picked field flips it, switching
    // fields starts from that field's natural order.
    function selectMailSort(kind: ViewSorting["kind"]) {
        const next: ViewSorting =
            sorting.kind === kind
                ? {kind, reversed: !sorting.reversed}
                : {kind, reversed: false};

        sorting = next;
        onSortingChanged?.(next);
    }
</script>

<!-- A span, not Tooltip.Trigger's default button: a button inside a menuitem would break the role
     and swallow the row's own click handling. -->
{#snippet sortHint(Icon: Component, text: string)}
    <Tooltip.Root delayDuration={300}>
        <Tooltip.Trigger>
            {#snippet child({props})}
                <span {...props} class="ml-auto flex items-center text-muted-foreground">
                    <Icon />
                </span>
            {/snippet}
        </Tooltip.Trigger>
        <Tooltip.Content side="right">{text}</Tooltip.Content>
    </Tooltip.Root>
{/snippet}

{#snippet categoryGroup(zone: string, label: string, emptyHint: string)}
    {@const list = dnd.zones[zone]}
    {@const isActive = zone === "active"}
    <!-- The group, not each item, owns the drop handling — an emptied list stays reachable -->
    <DndReorderZone {dnd} id={zone} role="group" aria-label={label}>
        <DropdownMenu.Label>{label}</DropdownMenu.Label>

        {#each list as category (category.kind)}
            {@const Icon = category.icon}
            <!-- The row the drag moves; the menu item inside it keeps its own clicks. -->
            <DndReorderElement {dnd} id={category.kind} handle={false}>
                <DropdownMenu.Item
                        closeOnSelect={false}
                        class={dnd.isDragging(category.kind) ? "opacity-40" : undefined}
                        aria-label={isActive ? `${category.name}: ${sortLabel(category)}` : category.name}
                        onSelect={() => isActive && toggleSort(category)}
                >
                    <DndReorderHandle
                            aria-hidden="true"
                            class="flex cursor-grab items-center active:cursor-grabbing"
                            onclick={(event) => event.stopPropagation()}
                    >
                        <DotsSixVerticalIcon />
                    </DndReorderHandle>
                    <Icon />
                    <span class="truncate">{category.name}</span>
                    {#if isActive && category.sort.reversible}
                        {@render sortHint(
                            reversed[category.kind] ? SortDescendingIcon : SortAscendingIcon,
                            sortLabel(category)
                        )}
                    {/if}
                </DropdownMenu.Item>
            </DndReorderElement>
        {/each}

        {#if isActive && deepest}
            <!-- The deepest active category is the last row, so rendering right after the loop
                 puts these under the group whose mails they actually order -->
            <div class="my-1 ml-5 border-l pl-1">
                <DropdownMenu.Label class="text-xs font-normal text-muted-foreground">
                    E-Mails hierin sortieren
                </DropdownMenu.Label>
                <DropdownMenu.RadioGroup value={sorting.kind}>
                    {#each MAIL_SORTS as option (option.kind)}
                        {@const selected = sorting.kind === option.kind}
                        {@const hint = selected && sorting.reversed ? option.label_reversed : option.label}
                        <DropdownMenu.RadioItem
                                value={option.kind}
                                closeOnSelect={false}
                                onSelect={() => selectMailSort(option.kind)}
                                aria-label={`${option.name}: ${hint}`}
                        >
                            <span class="truncate">{option.name}</span>
                            {#if selected}
                                {@render sortHint(
                                    sorting.reversed ? SortDescendingIcon : SortAscendingIcon,
                                    hint
                                )}
                            {/if}
                        </DropdownMenu.RadioItem>
                    {/each}
                </DropdownMenu.RadioGroup>
            </div>
        {/if}

        {#if list.length === 0}
            <p class="mx-2 my-1 rounded-xl border border-dashed px-2 py-3 text-center text-xs text-muted-foreground">
                {emptyHint}
            </p>
        {/if}
    </DndReorderZone>
{/snippet}

<DropdownMenu.Root>
    <DropdownMenu.Trigger>
        <!-- child, so the trigger *is* the chip: a div with a click handler is not something a
             keyboard can reach. Never the "set" look the filters have -- a listing is always
             grouped somehow, so that colour would say nothing here. -->
        {#snippet child({props})}
            <button {...props} type="button" class={cn(chip.root(), chip.action(), className)}>
                <TreeStructureIcon class="h-lh"/>
                <span>{$_("views.grouping.title")}</span>
                <CaretDownIcon class="h-lh"/>
            </button>
        {/snippet}
    </DropdownMenu.Trigger>

    <DropdownMenu.Content class="w-64" align="end">
        {@render categoryGroup("active", `Aktive Kategorien (${dnd.zones.active.length}/${MAX_ACTIVE})`, "Zum Gruppieren hierher ziehen")}
        {@render categoryGroup("inactive", "Inaktive Kategorien", "Alle Kategorien sind aktiv")}
    </DropdownMenu.Content>
</DropdownMenu.Root>
