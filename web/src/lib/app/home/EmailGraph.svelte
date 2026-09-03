<!--
    The heatmap on the home screen: a card per day of a year, tinted by how much mail arrived on
    it. The counts come over the home socket, so a mail landing while this is open fills its day
    in without a reload.
-->
<script lang="ts">
    import {locale, _} from "svelte-i18n";
    import UsageGraph from "$lib/app/usage-graph/UsageGraph.svelte";
    import type {UsageGraphState} from "$lib/app/usage-graph/UsageGraph.svelte";
    import {Button} from "$lib/components/ui/button";
    import {useRepositories} from "$lib/repository/repositories";

    const {home} = useRepositories();

    // The year the graph draws. Starts at the current one -- the year a page opened in is the
    // year it keeps, a page open across new year's eve is not worth a ticking clock.
    let year = $state(new Date().getFullYear());

    // Newest first, and always including the year on screen: that need not be one the mailbox has
    // mail in, and a switcher that does not show what it is switched to reads as broken.
    const years = $derived([...new Set([...home.availableYears, year])].sort((a, b) => b - a));

    // Asking is a message to the server, so it happens from an effect and not while rendering.
    // The current year needs no request; the socket sends it on its own.
    $effect(() => {
        home.requestYear(year);
    });

    const days = $derived(home.graph(year));
    const usage = $derived<UsageGraphState>(
        days === null ? {type: "loading"} : {type: "data", days}
    );

    /** "Mo., 4. Jan." — the year is already on the switcher above the grid. */
    const dayFormat = $derived(
        new Intl.DateTimeFormat($locale ?? undefined, {weekday: "short", day: "numeric", month: "short"})
    );

    // Built off the parts rather than parsed from the string: `new Date('2026-01-04')` is read as
    // UTC and would name the day before it west of Greenwich.
    function formatDay(date: string) {
        const [y, month, day] = date.split("-").map(Number);
        return dayFormat.format(new Date(y, month - 1, day));
    }
</script>

<section class="flex flex-col">
    <!-- Nothing to switch between while the mailbox holds one year. The row scrolls rather than
         wraps, so the buttons keep one line. -->
    {#if years.length > 1}
        <div class="flex max-w-full gap-1 overflow-x-auto">
            {#each years as option (option)}
                <Button
                        variant={option === year ? "default" : "outline"}
                        size="sm"
                        aria-pressed={option === year}
                        onclick={() => (year = option)}
                >
                    {option}
                </Button>
            {/each}
        </div>
    {/if}

    <!-- The grid is 53 weeks wide and does not wrap, so it scrolls on a narrow screen instead of
         pushing the page sideways. The tooltip is portalled out of here and does not mind that a
         box which scrolls in one axis clips in both. -->
    <div class="overflow-x-auto pt-4">
        <UsageGraph
                {year}
                state={usage}
                label={$_("home.graph.label", {values: {year}})}
                tooltip={dayTooltip}
        />
    </div>
</section>

<!-- The bubble around this is the tooltip's own, so what stands here is only what it says. -->
{#snippet dayTooltip({date, count}: {date: string; count: number})}
    <span class="font-medium">{$_("home.graph.day", {values: {count}})}</span>
    <span class="text-background/70">· {formatDay(date)}</span>
{/snippet}
