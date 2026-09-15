<script lang="ts">
    import {TagIcon, UserIcon, UsersIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import LabelFilter from "$lib/app/labels/LabelFilter.svelte";
    import InboxFilter from "$lib/app/filters/InboxFilter.svelte";
    import IsArchiveFilter from "$lib/app/filters/IsArchiveFilter.svelte";
    import IsUnreadFilter from "$lib/app/filters/IsUnreadFilter.svelte";
    import SenderFilter from "$lib/app/senders/SenderFilter.svelte";
    import GroupingSettings from "$lib/app/views/GroupingSettings.svelte";
    import type {ViewGrouping, ViewSorting} from "$lib/repository/ViewSocket";

    // The example holds what a view would: what it leaves out, and how what is left is arranged.
    // Nothing is saved yet -- this page is where the controls are tried out.
    let groupings: ViewGrouping[] = $state([{kind: "date_smart", reversed: false}]);
    let sorting: ViewSorting = $state({kind: "date", reversed: false});
</script>

<div class="flex flex-row flex-wrap items-center gap-2 px-2">
    <LabelFilter />
    <IsUnreadFilter />
    <IsArchiveFilter />
    <!-- The same control twice: who a mail came from, and who it went to. -->
    <SenderFilter title={$_("filters.from")} icon={UserIcon} />
    <SenderFilter title={$_("filters.to")} icon={UsersIcon} />
    <InboxFilter />

    <!-- The other side of the row: what is shown is set on the left, how it is arranged here. -->
    <GroupingSettings bind:groupings bind:sorting class="ml-auto" />
</div>
