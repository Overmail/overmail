<script lang="ts">
    import UsageGraph from "$lib/app/usage_graph/UsageGraph.svelte";
    import type {UsageGraphState} from "$lib/app/usage_graph/UsageGraph.svelte";
    import {Button} from "$lib/components/ui/button";
    import {ButtonGroup} from "$lib/components/ui/button-group";
    import {emailGraphRepository} from "$lib/repository/EmailGraphRepository";

    // The year the graph draws. Starts at the current one -- the year a page opened in is the
    // year it keeps, a page open across new year's eve is not worth a ticking clock.
    let year = $state(new Date().getFullYear());

    // Every year the mailbox has mail in, as the last answer reported them. Empty until then, so
    // the switcher appears with the first response rather than flashing a single year before it.
    let availableYears = $state<number[]>([]);

    let usage = $state<UsageGraphState>({type: 'loading'});
    let failed = $state(false);

    // Newest first, and always including the year on screen: that need not be one the mailbox has
    // mail in, and a switcher that does not show what it is switched to reads as broken.
    const years = $derived([...new Set([...availableYears, year])].sort((a, b) => b - a));

    // The layout only renders the app once there is a session, so the request is signed in.
    $effect(() => {
        const requested = year;
        usage = {type: 'loading'};
        failed = false;

        emailGraphRepository
            .getEmailGraph(requested)
            .then((graph) => {
                // Clicked on while this was in flight: the answer to the year nobody is looking
                // at any more is dropped, whichever order the two came back in.
                if (requested !== year) return;
                availableYears = graph.available_years;
                usage = {type: 'data', days: new Map(Object.entries(graph.days))};
            })
            .catch(() => {
                if (requested === year) failed = true;
            });
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

<section class="flex flex-col">
    <div class="flex flex-col flex-wrap items-start justify-between">
        <!-- Nothing to switch between while the mailbox holds one year. The row scrolls rather
             than wraps: a group of buttons that breaks across lines loses its shared border. -->
        {#if years.length > 1}
            <div class="max-w-full overflow-x-auto">
                <ButtonGroup>
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
                </ButtonGroup>
            </div>
        {/if}
    </div>

    {#if failed}
        <p class="text-muted-foreground text-xs">Die Mailzahlen konnten nicht geladen werden.</p>
    {:else}
        <!-- The grid is 53 weeks wide and does not wrap, so it scrolls on a narrow screen
             instead of pushing the page sideways. The tooltip is portalled out of here and does
             not mind that a box which scrolls in one axis clips in both. -->
        <div class="overflow-x-auto pt-4">
            <UsageGraph {year} state={usage} label="Mails pro Tag in {year}" tooltip={dayTooltip} />
        </div>
    {/if}
</section>

<!-- The bubble around this is the tooltip's own, so what stands here is only what it says. -->
{#snippet dayTooltip({date, count}: {date: string; count: number})}
    <span class="font-medium">
        {count === 0 ? 'Keine Mail' : count === 1 ? '1 Mail' : `${count} Mails`}
    </span>
    <span class="text-background/70">· {formatDay(date)}</span>
{/snippet}
