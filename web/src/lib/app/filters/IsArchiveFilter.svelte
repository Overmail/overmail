<!--
    Where a mail sits: in the inbox, in the archive, or in spam.

    Unset is not "everything" here but "not archived": a mailbox is what is left to do, and a
    listing that starts by mixing years of archived mail into it would be a worse default than a
    filter nobody set. The button adds the archived ones to that and says so ("Auch archivierte")
    -- spam stays out of that one click, it is looked for on purpose. The menu is for any other
    combination, including none at all, which does let everything through.

    The values are the server's own (`EmailArchiveAction`), so what is picked here is what
    `ViewFilter.archivedState` holds; an empty selection is the null that restricts nothing.
-->
<script lang="ts">
    import {ArchiveIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import ToggleFilter from "$lib/app/filters/ToggleFilter.svelte";
    import type {ViewArchivedState} from "$lib/repository/ViewSocket";

    let {
        selected = $bindable(["Unarchive"]),
        onStateAdded,
        onStateRemoved,
        class: className,
    }: {
        /** The states that are on. Starts at the inbox alone; empty would be every mail. */
        selected?: ViewArchivedState[];
        onStateAdded?: (value: ViewArchivedState) => void;
        onStateRemoved?: (value: ViewArchivedState) => void;
        class?: string;
    } = $props();

    // The inbox first: it is where a listing starts, and the menu reads as the listing grows
    // downwards from it. Spam last -- it is looked for on purpose, not something that comes up
    // while sorting mail.
    const STATES = $derived([
        {value: "Unarchive" as const, label: $_("filters.archive.inbox")},
        {value: "Archive" as const, label: $_("filters.archive.archived")},
        {value: "Spam" as const, label: $_("filters.archive.spam")},
    ]);
</script>

<ToggleFilter
        states={STATES}
        bind:selected
        unset={["Unarchive"]}
        primary="Archive"
        quickLabel={$_("filters.archive.quick")}
        icon={ArchiveIcon}
        {onStateAdded}
        {onStateRemoved}
        class={className}
/>
