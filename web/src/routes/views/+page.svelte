<script lang="ts" module>
    import type {Component} from "svelte";

    type GroupCategory = {
        key: string;
        name: string;
        icon: Component;
        sort: {
            key: string;
            reversible?: boolean;
            label: string;
            label_reversed?: string;
        };
    };
</script>

<script lang="ts">
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {Button} from "$lib/components/ui/button";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import * as Popover from "$lib/components/ui/popover"
    import {DndReorderElement, DndReorderHandle, DndReorderZone} from "$lib/components/dnd";
    import {DndReorder} from "$lib/hooks/dnd-reorder.svelte";
    import {moveTo} from "$lib/hooks/dnd-reorder";
    import {
        ArchiveIcon,
        CalendarDotIcon,
        CalendarDotsIcon,
        CalendarIcon,
        CalendarStarIcon, CaretDownIcon, DotsSixVerticalIcon, EyeglassesIcon,
        PersonSimpleIcon,
        SortAscendingIcon,
        SortDescendingIcon,
        TagIcon,
        UsersIcon
    } from "phosphor-svelte";
    import LabelFilter from "$lib/app/labels/LabelFilter.svelte";

    const MAX_ACTIVE = 4;

    // `name` is what the row reads; `label`/`label_reversed` are the full tooltip
    // sentences for the two directions, same rule as a category's `sort`.
    const MAIL_SORTS = [
        { key: "date", name: "Datum", label: "Nach Datum, neueste zuerst", label_reversed: "Nach Datum, älteste zuerst" },
        { key: "sender", name: "Absender", label: "Absender, A–Z", label_reversed: "Absender, Z–A" },
        { key: "subject", name: "Betreff", label: "Betreff, A–Z", label_reversed: "Betreff, Z–A" },
    ];

    const CATEGORIES: GroupCategory[] = [
        { key: "date-smart", name: "Datum (intelligent)", icon: CalendarStarIcon, sort: { key: "date", reversible: true, label: "Nach Datum, neueste zuerst", label_reversed: "Nach Datum, älteste zuerst" } },
        { key: "read", name: "Gelesen", icon: EyeglassesIcon, sort: { key: "read", label: "Gelesene zuerst", reversible: true, label_reversed: "Ungelesene zuerst" } },
        { key: "date-month", name: "Monat", icon: CalendarDotsIcon, sort: { key: "date", reversible: true, label: "Nach Monat, neueste zuerst", label_reversed: "Nach Monat, älteste zuerst" } },
        { key: "date-year", name: "Jahr", icon: CalendarDotIcon, sort: { key: "date", reversible: true, label: "Nach Jahr, neueste zuerst", label_reversed: "Nach Jahr, älteste zuerst" } },
        { key: "date-day", name: "Tag", icon: CalendarIcon, sort: { key: "date", reversible: true, label: "Nach Tag, neueste zuerst", label_reversed: "Nach Tag, älteste zuerst" } },
        { key: "sender", name: "Absender", icon: PersonSimpleIcon, sort: { key: "sender", reversible: true, label: "Absender, A–Z", label_reversed: "Absender, Z–A" } },
        { key: "imap-account", name: "E-Mail-Konto", icon: UsersIcon, sort: { key: "imap-account", reversible: true, label: "E-Mail-Konto, A–Z", label_reversed: "E-Mail-Konto, Z–A" } },
        { key: "Archive", name: "Archiviert", icon: ArchiveIcon, sort: { key: "archive", reversible: true, label: "Aktive zuerst", label_reversed: "Archivierte zuerst" } },
    ];

    const byKey = new Map(CATEGORIES.map((category) => [category.key, category]));

    // Membership is position: which list a category is in is the whole of whether it groups.
    let activeKeys = $state(CATEGORIES.slice(0, MAX_ACTIVE).map((category) => category.key));
    let inactiveKeys = $state(CATEGORIES.slice(MAX_ACTIVE).map((category) => category.key));

    const dnd = new DndReorder({
        zones: () => ({
            active: activeKeys.map((key) => byKey.get(key)!),
            inactive: inactiveKeys.map((key) => byKey.get(key)!),
        }),
        id: (category) => category.key,
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
                active: next.active.filter((key) => key !== evicted),
                inactive: [evicted, ...next.inactive],
            };
        },
        onDrop: ({order}) => {
            activeKeys = order.active;
            inactiveKeys = order.inactive;
        },
    });

    // Only the deepest active category holds mails directly, so this is a single
    // setting rather than one per category.
    let mailSortKey = $state(MAIL_SORTS[0].key);
    let mailSortReversed = $state(false);

    // Which categories run their sort reversed, keyed by category. Separate from
    // `sort` above, which is static config — and it survives reordering.
    let reversedSort = $state<Record<string, boolean>>({});

    const deepest = $derived(dnd.zones.active.at(-1));

    function sortLabel(category: GroupCategory) {
        return reversedSort[category.key]
            ? category.sort.label_reversed ?? category.sort.label
            : category.sort.label;
    }

    function toggleSort(category: GroupCategory) {
        if (!category.sort.reversible) return;
        reversedSort[category.key] = !reversedSort[category.key];
    }

    // Reversing is not an option of its own: picking the already picked field
    // flips it, switching fields starts from that field's natural order.
    function selectMailSort(key: string) {
        if (mailSortKey === key) mailSortReversed = !mailSortReversed;
        else mailSortReversed = false;
    }
</script>

<!-- A span, not Tooltip.Trigger's default button: a button inside a menuitem
     would break the role and swallow the row's own click handling. -->
{#snippet sortHint(Icon: Component, text: string)}
    <Tooltip.Root delayDuration={300}>
        <Tooltip.Trigger>
            {#snippet child({ props })}
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

        {#each list as category (category.key)}
            {@const Icon = category.icon}
            <!-- The row the drag moves; the menu item inside it keeps its own clicks. -->
            <DndReorderElement {dnd} id={category.key} handle={false}>
                <DropdownMenu.Item
                        closeOnSelect={false}
                        class={dnd.isDragging(category.key) ? "opacity-40" : undefined}
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
                            reversedSort[category.key] ? SortDescendingIcon : SortAscendingIcon,
                            sortLabel(category)
                        )}
                    {/if}
                </DropdownMenu.Item>
            </DndReorderElement>
        {/each}

        {#if isActive && deepest}
            <!-- The deepest active category is the last row, so rendering right after
                 the loop puts these under the group whose mails they actually order -->
            <div class="my-1 ml-5 border-l pl-1">
                <DropdownMenu.Label class="text-xs font-normal text-muted-foreground">
                    E-Mails hierin sortieren
                </DropdownMenu.Label>
                <DropdownMenu.RadioGroup bind:value={mailSortKey}>
                    {#each MAIL_SORTS as option (option.key)}
                        {@const selected = mailSortKey === option.key}
                        {@const hint = selected && mailSortReversed ? option.label_reversed : option.label}
                        <DropdownMenu.RadioItem
                                value={option.key}
                                closeOnSelect={false}
                                onSelect={() => selectMailSort(option.key)}
                                aria-label={`${option.name}: ${hint}`}
                        >
                            <span class="truncate">{option.name}</span>
                            {#if selected}
                                {@render sortHint(
                                    mailSortReversed ? SortDescendingIcon : SortAscendingIcon,
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

<div class="flex flex-row">
    <DropdownMenu.Root>

        <DropdownMenu.Trigger>
            <Button>View options</Button>
        </DropdownMenu.Trigger>

        <DropdownMenu.Content class="w-56" align="start">
            <DropdownMenu.Sub>
                <DropdownMenu.SubTrigger>Gruppieren</DropdownMenu.SubTrigger>
                <DropdownMenu.SubContent class="w-64">
                    {@render categoryGroup("active", `Aktive Kategorien (${dnd.zones.active.length}/${MAX_ACTIVE})`, "Zum Gruppieren hierher ziehen")}
                    {@render categoryGroup("inactive", "Inaktive Kategorien", "Alle Kategorien sind aktiv")}
                </DropdownMenu.SubContent>
            </DropdownMenu.Sub>
        </DropdownMenu.Content>
    </DropdownMenu.Root>
</div>

<LabelFilter />