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
    import {
        ArchiveIcon,
        CalendarDotIcon,
        CalendarDotsIcon,
        CalendarIcon,
        CalendarStarIcon, DotsSixVerticalIcon, EyeglassesIcon,
        PersonSimpleIcon,
        UsersIcon
    } from "phosphor-svelte";

    const MAX_ACTIVE = 2;
    const FLIP_MS = 150;

    // One ordered list; the first `activeCount` entries are the active group.
    // Membership is therefore a consequence of position, never stored separately.
    let groupCategories = $state<GroupCategory[]>([
        // the first MAX_ACTIVE entries are the initially active ones
        { key: "date-smart", name: "Datum (intelligent)", icon: CalendarStarIcon, sort: { key: "date", reversible: true, label: "nach Datum", label_reversed: "nach Datum" } },
        { key: "read", name: "Gelesen", icon: EyeglassesIcon, sort: { key: "read", label: "Gelesene zuerst", reversible: true, label_reversed: "Ungelesene zuerst" } },
        { key: "date-month", name: "Monat", icon: CalendarDotsIcon, sort: { key: "date", reversible: true, label: "nach Datum", label_reversed: "nach Datum" } },
        { key: "date-year", name: "Jahr", icon: CalendarDotIcon, sort: { key: "date", reversible: true, label: "nach Datum", label_reversed: "nach Datum" } },
        { key: "date-day", name: "Tag", icon: CalendarIcon, sort: { key: "date", reversible: true, label: "nach Datum", label_reversed: "nach Datum" } },
        { key: "sender", name: "Absender", icon: PersonSimpleIcon, sort: { key: "sender", reversible: true, label: "nach Absender", label_reversed: "nach Absender" } },
        { key: "imap-account", name: "E-Mail-Konto", icon: UsersIcon, sort: { key: "imap-account", reversible: true, label: "nach E-Mail-Konto", label_reversed: "nach E-Mail-Konto" } },
        { key: "Archive", name: "Archiviert", icon: ArchiveIcon, sort: { key: "archive", reversible: true, label: "nach Archiviert"}, label_reversed: "nach Archiviert" }
    ]);

    let activeCount = $state(MAX_ACTIVE);
    let draggingKey = $state<string | null>(null);

    // flip animates with transforms, so getBoundingClientRect() reports positions
    // mid-flight — measurements are only trusted once the list has settled.
    let settledAt = 0;

    const active = $derived(groupCategories.slice(0, activeCount));
    const inactive = $derived(groupCategories.slice(activeCount));

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
                >
                    <span
                            draggable="true"
                            aria-hidden="true"
                            class="flex cursor-grab items-center active:cursor-grabbing"
                            ondragstart={(event) => onDragStart(event, category)}
                            ondragend={() => (draggingKey = null)}
                    >
                        <DotsSixVerticalIcon />
                    </span>
                    <Icon />
                    {category.name}
                </DropdownMenu.Item>
            </div>
        {/each}

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
                <DropdownMenu.SubContent>
                    {@render categoryGroup(`Aktive Kategorien (${activeCount}/${MAX_ACTIVE})`, active, true, "Zum Gruppieren hierher ziehen")}
                    {@render categoryGroup("Inaktive Kategorien", inactive, false, "Alle Kategorien sind aktiv")}
                </DropdownMenu.SubContent>
            </DropdownMenu.Sub>
        </DropdownMenu.Content>
    </DropdownMenu.Root>
</div>
