<!--
    The read state a listing is cut down to. "Ungelesen" by one click, the menu for the other way
    round or for both at once.

    Both states ticked is the same as none as far as the listing goes -- every mail is one or the
    other -- but it is not the same thing to say, so the chip keeps them apart and leaves the
    reading of it to whoever saves the filter: `ViewFilter.readState` is false for unread alone,
    true for read alone, and null for anything else.
-->
<script lang="ts" module>
    /** What a mail can be, and what this filter stores for it. */
    export type ReadState = "unread" | "read";
</script>

<script lang="ts">
    import {EyeglassesIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import ToggleFilter from "$lib/app/filters/ToggleFilter.svelte";

    let {
        selected = $bindable([]),
        onStateAdded,
        onStateRemoved,
        class: className,
    }: {
        /** The states that are on; empty is every mail, which is no filter. */
        selected?: ReadState[];
        onStateAdded?: (value: ReadState) => void;
        onStateRemoved?: (value: ReadState) => void;
        class?: string;
    } = $props();

    // Unread first: it is what the chip offers on its own, and what this filter is for.
    const STATES = $derived([
        {value: "unread" as const, label: $_("filters.read.unread")},
        {value: "read" as const, label: $_("filters.read.read")},
    ]);
</script>

<ToggleFilter
        states={STATES}
        bind:selected
        primary="unread"
        icon={EyeglassesIcon}
        {onStateAdded}
        {onStateRemoved}
        class={className}
/>
