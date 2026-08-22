<script lang="ts">
    // The shut state of the card. Two states only -- working through a backlog, or working at all
    // -- everything finer is in the panel.
    import {SparkleIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import type {AgentProcessStatus} from "$lib/repository/AgentRepository";

    let {status, onclick, expanded}: {
        status: AgentProcessStatus | null;
        onclick: () => void;
        expanded: boolean;
    } = $props();

    const queue = $derived(status?.queue ?? null);
    const total = $derived(queue ? queue.processed + queue.queued : 0);
    const percent = $derived(total === 0 ? 0 : Math.round(((queue?.processed ?? 0) / total) * 100));

    // Working at all -- on one of ours, or on a foreign mail that ours are queued behind: for a
    // glance from across the room the difference does not matter, the panel has it. Not connected
    // yet reads as idle, there is nothing to report either way.
    const busy = $derived(status !== null && status.work.state !== 'idle');
    // A whole mailbox is being worked through, so the progress is worth a bar. Outside a backlog
    // there are a handful of mails at most and the bar would sit at 99 % saying nothing.
    const importing = $derived(busy && queue?.mode === 'backlog');
</script>

<Button
        variant="secondary"
        aria-label="Agent"
        aria-expanded={expanded}
        {onclick}
        class="flex flex-row items-center h-12 min-w-12 w-fit drop-shadow-xl rounded-4xl px-3.5 bg-background"
>
    <!-- Nothing to the left of the sparkle while the agent idles: the button falls back to the
         circle it is at its minimum width. -->
    {#if busy}
        <div class="flex flex-col items-start mr-0.5 pr-2 border-r">
            <div class="flex flex-row items-center justify-between gap-0.5 w-full">
                <span class="text-xs text-muted-foreground">{importing ? 'Importiere' : 'Verarbeite'}</span>
                {#if importing}
                    <span class="text-xs text-muted-foreground">{percent}%</span>
                {/if}
            </div>
            {#if importing}
                <div class="bg-muted h-1.5 w-24 overflow-hidden rounded-full">
                    <div class="bg-primary h-full transition-[width]" style="width: {percent}%"></div>
                </div>
            {/if}
        </div>
    {/if}
    <SparkleIcon class="size-6" />
</Button>
