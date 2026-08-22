<script lang="ts">
    import UsageGraph from "$lib/app/usage_graph/UsageGraph.svelte";
    import type {UsageGraphState} from "$lib/app/usage_graph/UsageGraph.svelte";
    import {emailGraphRepository} from "$lib/repository/EmailGraphRepository";

    // The year the graph draws. Read once on load: a page that is open across new year's eve
    // showing the old year is not worth a ticking clock.
    const year = new Date().getFullYear();

    let usage = $state<UsageGraphState>({type: 'loading'});
    let failed = $state(false);

    // The layout only renders the app once there is a session, so the request is signed in.
    $effect(() => {
        emailGraphRepository
            .getEmailGraph(year)
            .then((graph) => (usage = {type: 'data', days: new Map(Object.entries(graph.days))}))
            .catch(() => (failed = true));
    });

    /** "Mo., 4. Jan." — the year is already in the heading above the grid. */
    const dayFormat = new Intl.DateTimeFormat(undefined, {weekday: 'short', day: 'numeric', month: 'short'});

    // Built off the parts rather than parsed from the string: `new Date('2026-01-04')` is read as
    // UTC and would name the day before it west of Greenwich.
    function formatDay(date: string) {
        const [y, month, day] = date.split('-').map(Number);
        return dayFormat.format(new Date(y, month - 1, day));
    }
</script>

<section class="flex flex-col gap-3">
    <div class="flex flex-col gap-0.5">
        <h2 class="text-sm font-medium">Mails in {year}</h2>
        <p class="text-muted-foreground text-xs">
            Wie viele Mails an jedem Tag angekommen sind.
        </p>
    </div>

    {#if failed}
        <p class="text-muted-foreground text-xs">Die Mailzahlen konnten nicht geladen werden.</p>
    {:else}
        <!-- The grid is 53 weeks wide and does not wrap, so it scrolls on a narrow screen
             instead of pushing the page sideways. The padding is for the tooltip: a box that
             scrolls in one axis clips in both, and the tooltip of the top row stands above
             the grid. -->
        <div class="overflow-x-auto pt-8">
            <UsageGraph {year} state={usage} label="Mails pro Tag in {year}" tooltip={dayTooltip} />
        </div>
    {/if}
</section>

{#snippet dayTooltip({date, count}: {date: string; count: number})}
    <div class="bg-popover text-popover-foreground rounded-md border px-2 py-1 text-xs whitespace-nowrap shadow-md">
        <span class="font-medium">
            {count === 0 ? 'Keine Mail' : count === 1 ? '1 Mail' : `${count} Mails`}
        </span>
        <span class="text-muted-foreground">· {formatDay(date)}</span>
    </div>
{/snippet}
