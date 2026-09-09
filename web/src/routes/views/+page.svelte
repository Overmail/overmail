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
    import {flip} from "svelte/animate";
    import {crossfade} from "svelte/transition";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {
        ArchiveIcon,
        CalendarDotIcon,
        CalendarDotsIcon,
        CalendarIcon,
        CalendarStarIcon, DotsSixVerticalIcon, EyeglassesIcon,
        PersonSimpleIcon,
        SortAscendingIcon,
        SortDescendingIcon,
        UsersIcon
    } from "phosphor-svelte";

    const MAX_ACTIVE = 2;
    const FLIP_MS = 150;

    // `name` is what the row reads; `label`/`label_reversed` are the full tooltip
    // sentences for the two directions, same rule as a category's `sort`.
    const MAIL_SORTS = [
        { key: "date", name: "Datum", label: "Nach Datum, neueste zuerst", label_reversed: "Nach Datum, älteste zuerst" },
        { key: "sender", name: "Absender", label: "Absender, A–Z", label_reversed: "Absender, Z–A" },
        { key: "subject", name: "Betreff", label: "Betreff, A–Z", label_reversed: "Betreff, Z–A" },
        { key: "size", name: "Größe", label: "Größte zuerst", label_reversed: "Kleinste zuerst" },
    ];

    // One ordered list; the first `activeCount` entries are the active group.
    // Membership is therefore a consequence of position, never stored separately.
    let groupCategories = $state<GroupCategory[]>([
        // the first MAX_ACTIVE entries are the initially active ones
        { key: "date-smart", name: "Datum (intelligent)", icon: CalendarStarIcon, sort: { key: "date", reversible: true, label: "Nach Datum, neueste zuerst", label_reversed: "Nach Datum, älteste zuerst" } },
        { key: "read", name: "Gelesen", icon: EyeglassesIcon, sort: { key: "read", label: "Gelesene zuerst", reversible: true, label_reversed: "Ungelesene zuerst" } },
        { key: "date-month", name: "Monat", icon: CalendarDotsIcon, sort: { key: "date", reversible: true, label: "Nach Monat, neueste zuerst", label_reversed: "Nach Monat, älteste zuerst" } },
        { key: "date-year", name: "Jahr", icon: CalendarDotIcon, sort: { key: "date", reversible: true, label: "Nach Jahr, neueste zuerst", label_reversed: "Nach Jahr, älteste zuerst" } },
        { key: "date-day", name: "Tag", icon: CalendarIcon, sort: { key: "date", reversible: true, label: "Nach Tag, neueste zuerst", label_reversed: "Nach Tag, älteste zuerst" } },
        { key: "sender", name: "Absender", icon: PersonSimpleIcon, sort: { key: "sender", reversible: true, label: "Absender, A–Z", label_reversed: "Absender, Z–A" } },
        { key: "imap-account", name: "E-Mail-Konto", icon: UsersIcon, sort: { key: "imap-account", reversible: true, label: "E-Mail-Konto, A–Z", label_reversed: "E-Mail-Konto, Z–A" } },
        { key: "Archive", name: "Archiviert", icon: ArchiveIcon, sort: { key: "archive", reversible: true, label: "Aktive zuerst", label_reversed: "Archivierte zuerst" } },
    ]);

    let activeCount = $state(MAX_ACTIVE);
    let draggingKey = $state<string | null>(null);

    // Which categories run their sort reversed, keyed by category. Separate from
    // `sort` above, which is static config — and it survives reordering.
    let reversedSort = $state<Record<string, boolean>>({});

    // Only the deepest active category holds mails directly, so this is a single
    // setting rather than one per category.
    let mailSortKey = $state(MAIL_SORTS[0].key);
    let mailSortReversed = $state(false);

    // flip animates with transforms, so getBoundingClientRect() reports positions
    // mid-flight — measurements are only trusted once the list has settled.
    let settledAt = 0;

    const active = $derived(groupCategories.slice(0, activeCount));
    const inactive = $derived(groupCategories.slice(activeCount));
    const deepest = $derived(active.at(-1));

    // Changing group means leaving one {#each} and entering the other, which
    // animate:flip cannot follow — crossfade bridges the two.
    const [send, receive] = crossfade({ duration: FLIP_MS });

    function onDragStart(event: DragEvent, category: GroupCategory) {
        draggingKey = category.key;
        settledAt = 0;
        // Firefox starts no drag without a payload
        event.dataTransfer?.setData("text/plain", category.key);
        if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
    }

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

    function allowDrop(event: DragEvent) {
        event.preventDefault();
        if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
    }

    /**
     * Moves the dragged category to `slot` within the destination group. The
     * group only owns a fixed range of the list, so clamping `slot` into that
     * range is what pushes a surplus category over the boundary — no explicit
     * eviction anywhere.
     */
    function moveTo(toActive: boolean, slot: number) {
        const key = draggingKey;
        if (key === null) return;

        const from = groupCategories.findIndex(category => category.key === key);
        if (from === -1) return;

        const wasActive = from < activeCount;
        const nextActiveCount = toActive
            ? wasActive ? activeCount : Math.min(activeCount + 1, MAX_ACTIVE)
            : wasActive ? activeCount - 1 : activeCount;

        const first = toActive ? 0 : nextActiveCount;
        const last = toActive ? nextActiveCount - 1 : groupCategories.length - 1;
        const to = Math.min(Math.max(first + slot, first), last);

        // re-inserting at `from` would rebuild the same list
        if (to === from && nextActiveCount === activeCount) return;

        const [moved] = groupCategories.splice(from, 1);
        groupCategories.splice(to, 0, moved);
        activeCount = nextActiveCount;
        settledAt = performance.now() + FLIP_MS;
    }

    /**
     * The slot is how many of the group's other items have their midpoint above
     * the cursor — a pure function of the cursor position and settled geometry,
     * and monotonic in `clientY`. Two neighbours therefore agree on their shared
     * edge (no flicker there), and once moved the cursor sits over the dragged
     * item itself, which buys a full item height of hysteresis.
     *
     * Listening on the group rather than on each item also means hovering the
     * label inserts at the top, not at the bottom.
     */
    function onDragOver(event: DragEvent & { currentTarget: HTMLElement }, isActive: boolean) {
        allowDrop(event);
        if (draggingKey === null) return;
        // mid-flip rects are in transit and would misplace the item
        if (performance.now() < settledAt) return;

        let slot = 0;
        for (const element of event.currentTarget.querySelectorAll<HTMLElement>("[data-key]")) {
            if (element.dataset.key === draggingKey) continue;
            const rect = element.getBoundingClientRect();
            if (event.clientY > rect.top + rect.height / 2) slot++;
        }

        moveTo(isActive, slot);
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

{#snippet categoryGroup(label: string, list: GroupCategory[], isActive: boolean, emptyHint: string)}
    <!-- The group, not each item, owns the drop handling — an emptied list stays reachable -->
    <div
            role="group"
            aria-label={label}
            ondragover={(event) => onDragOver(event, isActive)}
            ondrop={(event) => event.preventDefault()}
    >
        <DropdownMenu.Label>{label}</DropdownMenu.Label>

        {#each list as category (category.key)}
            {@const Icon = category.icon}
            <div
                    animate:flip={{ duration: FLIP_MS }}
                    in:receive={{ key: category.key }}
                    out:send={{ key: category.key }}
            >
                <DropdownMenu.Item
                        closeOnSelect={false}
                        data-key={category.key}
                        class={draggingKey === category.key ? "opacity-40" : undefined}
                        aria-label={isActive ? `${category.name}: ${sortLabel(category)}` : category.name}
                        onSelect={() => isActive && toggleSort(category)}
                >
                    <span
                            draggable="true"
                            aria-hidden="true"
                            class="flex cursor-grab items-center active:cursor-grabbing"
                            ondragstart={(event) => onDragStart(event, category)}
                            ondragend={() => (draggingKey = null)}
                            onclick={(event) => event.stopPropagation()}
                    >
                        <DotsSixVerticalIcon />
                    </span>
                    <Icon />
                    <span class="truncate">{category.name}</span>
                    {#if isActive && category.sort.reversible}
                        {@render sortHint(
                            reversedSort[category.key] ? SortDescendingIcon : SortAscendingIcon,
                            sortLabel(category)
                        )}
                    {/if}
                </DropdownMenu.Item>
            </div>
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
    </div>
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
                    {@render categoryGroup(`Aktive Kategorien (${activeCount}/${MAX_ACTIVE})`, active, true, "Zum Gruppieren hierher ziehen")}
                    {@render categoryGroup("Inaktive Kategorien", inactive, false, "Alle Kategorien sind aktiv")}
                </DropdownMenu.SubContent>
            </DropdownMenu.Sub>
        </DropdownMenu.Content>
    </DropdownMenu.Root>
</div>
