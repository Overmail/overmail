<!--
    A filter over a fixed set of states, as a split chip: the button turns the filter on and off,
    the caret beside it opens the states to tick one by one.

    Not set is what the quiet chip means, and what the button gets back to. Usually that is
    nothing picked -- an empty set restricts nothing -- but a filter can start somewhere else:
    the archive one is unset at "not archived", because that is what a mailbox is, and its button
    adds the archived mails to that. [unset] is what says which.

    Split rather than a menu alone: the one thing a filter like this is used for -- "only unread",
    "only archived" -- is a single click, and everything else is a click more.
-->
<script lang="ts" module>
    /** One state a filter can be on: what the caller stores for it, and what it is called. */
    export type FilterState<T extends string = string> = {
        value: T;
        label: string;
    };
</script>

<script lang="ts" generics="T extends string">
    import {CaretDownIcon} from "phosphor-svelte";
    import type {Component} from "svelte";
    import {_} from "svelte-i18n";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {filterChip} from "$lib/app/filters/chip";
    import {summarisePicked} from "$lib/app/filters/summarise";

    let {
        states,
        // Before `selected`, which defaults to it: a filter nobody has bound starts unset.
        unset: unsetStates = [],
        selected = $bindable([...unsetStates]),
        primary = states[0].value,
        quickLabel,
        icon,
        onStateAdded,
        onStateRemoved,
        class: className,
    }: {
        /** Every state, in the order the menu lists them and the chip names them. */
        states: FilterState<T>[];
        /**
         * The states that are on; empty is no restriction, which is the filter being off. Written
         * here as they are toggled, so a caller that only reads the selection can bind and stop.
         */
        selected?: T[];
        /**
         * The selection that counts as the filter not being set -- what the listing shows until
         * somebody says otherwise. Empty for most filters; see the note at the top.
         */
        unset?: T[];
        /**
         * What the chip's own button adds to [unset] -- the one thing this filter is mostly used
         * for. Defaults to the first state.
         */
        primary?: T;
        /**
         * What the chip calls that one click -- the offer while the filter is unset, and what it
         * stands for once exactly that is on. Defaults to the primary state's own name; the
         * archive filter says "Auch archivierte", because that is what adding it to the inbox
         * means, while the row in the menu stays "Archiviert".
         */
        quickLabel?: string;
        icon?: Component;
        /** One state was turned on. For a caller that saves a change rather than the whole set. */
        onStateAdded?: (value: T) => void;
        onStateRemoved?: (value: T) => void;
        /** Where the chip sits; it brings no margin of its own. */
        class?: string;
    } = $props();

    const Icon = $derived(icon);

    /** Whatever [unset] holds is not a filter, and the chip says so by staying quiet. */
    const active = $derived.by(
        () => selected.length !== unsetStates.length || !unsetStates.every((value) => selected.includes(value))
    );

    const chip = $derived(filterChip({active}));

    /**
     * What the chip names: what is on beyond [unset], which for the usual click is the one state
     * the button added. What is in [unset] is not worth naming -- the chip stands for the
     * difference from it, and "Posteingang, Archiviert" says no more than "Archiviert" does.
     *
     * A selection that is nothing but a piece of [unset] -- or nothing at all, which restricts
     * nothing -- has no such difference to name, so it falls back to what is actually picked.
     */
    const named = $derived.by(() => {
        const beyond = states.filter(
            (state) => selected.includes(state.value) && !unsetStates.includes(state.value)
        );
        if (beyond.length > 0) return beyond;

        return states.filter((state) => selected.includes(state.value));
    });

    const summary = $derived(summarisePicked(named.map((state) => state.label)));

    const primaryLabel = $derived(quickLabel ?? states.find((state) => state.value === primary)?.label ?? "");

    /** Whether what is on is exactly the one click the button offers, and nothing else. */
    const isQuick = $derived.by(() => {
        const quick = [...unsetStates, primary];

        return selected.length === quick.length && quick.every((value) => selected.includes(value));
    });

    const text = $derived.by(() => {
        // Not set reads as the offer, the way a button says what it does: "Ungelesen" is what
        // clicking gets you, not what is on. And the offer taken up reads as itself rather than
        // as the states behind it -- it is one thing to the user, not a set.
        if (!active || isQuick) return primaryLabel;
        // Set to nothing at all, which lets everything through -- the one state that names
        // itself by what it is not.
        if (named.length === 0) return $_("filters.all");

        const shown = summary.shown.join(", ");

        return summary.rest === 0
            ? shown
            : `${shown} ${$_("filters.more", {values: {count: summary.rest}})}`;
    });

    /** The chip's own button: the filter as a whole, on or off. */
    function toggle() {
        // Set goes back to unset, whatever the menu picked -- the button says "this filter", not
        // "this state", and a second click on a set filter is how it is taken back.
        pick(active ? unsetStates : [...unsetStates, primary]);
    }

    function add(value: T) {
        pick([...selected, value]);
    }

    function remove(value: T) {
        pick(selected.filter((candidate) => candidate !== value));
    }

    /**
     * The one way the selection changes: it is put in the order the states are declared, so the
     * chip reads the same however they were picked, and the caller hears about every state that
     * came or went -- a click on the button can be several at once.
     */
    function pick(next: T[]) {
        const ordered = states.map((state) => state.value).filter((value) => next.includes(value));
        const added = ordered.filter((value) => !selected.includes(value));
        const removed = selected.filter((value) => !ordered.includes(value));

        selected = ordered;
        for (const value of added) onStateAdded?.(value);
        for (const value of removed) onStateRemoved?.(value);
    }
</script>

<div class={chip.root({class: className})}>
    <button type="button" class={chip.action()} onclick={toggle}>
        {#if Icon}
            <Icon class="h-lh" weight={active ? "fill" : "regular"}/>
        {/if}
        <span class={active ? "font-semibold" : undefined}>{text}</span>
    </button>

    <span class={chip.divider()}></span>

    <DropdownMenu.Root>
        <DropdownMenu.Trigger>
            <!-- child, so the trigger *is* the half: one button inside another is not a button. -->
            {#snippet child({props})}
                <button
                        {...props}
                        type="button"
                        class={chip.action({class: "px-1.5"})}
                        aria-label={$_("filters.states")}
                >
                    <CaretDownIcon class="h-lh"/>
                </button>
            {/snippet}
        </DropdownMenu.Trigger>

        <DropdownMenu.Content align="start" class="w-48">
            {#each states as state (state.value)}
                <!-- The menu stays open: ticking one state is rarely all somebody came for. -->
                <DropdownMenu.CheckboxItem
                        closeOnSelect={false}
                        checked={selected.includes(state.value)}
                        onCheckedChange={(checked) => (checked ? add(state.value) : remove(state.value))}
                >
                    {state.label}
                </DropdownMenu.CheckboxItem>
            {/each}
        </DropdownMenu.Content>
    </DropdownMenu.Root>
</div>
