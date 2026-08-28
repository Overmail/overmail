<script lang="ts">
    // The shut state of the card: the way into the panel, and the one thing worth saying while it is
    // shut -- that the agent is working.
    import {SparkleIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {agentQueue} from "./AgentQueueStore.svelte";

    let {onclick, expanded}: {
        onclick: () => void;
        expanded: boolean;
    } = $props();

    // Its own claim on the socket, held for as long as the button exists: the indicator is the whole
    // reason a reader who never opens the panel would want the connection at all.
    $effect(() => {
        agentQueue.open();
        return () => agentQueue.close();
    });

    const isWorking = $derived(agentQueue.isWorking);

    /** How much is still owed, once it is worth saying. One mail is what the spinner already says. */
    const waiting = $derived(agentQueue.pending > 1 ? agentQueue.pending : null);
</script>

<Button
        variant="secondary"
        aria-label={isWorking ? `Agent liest Mails${waiting ? `, ${waiting} warten` : ""}` : "Agent"}
        aria-expanded={expanded}
        {onclick}
        class="flex flex-row items-center gap-2 h-12 min-w-12 w-fit drop-shadow-xl rounded-4xl px-3.5 bg-background"
>
    <!-- The icon and the ring around it are one thing: the ring turns while the agent is reading and
         is not rendered at all otherwise, so the button is exactly as loud as there is news. -->
    <span class="relative flex size-6 items-center justify-center">
        <SparkleIcon class="size-6" />

        {#if isWorking}
            <span
                    class="border-primary/30 border-t-primary absolute -inset-1 animate-spin rounded-full border-2"
                    aria-hidden="true"
            ></span>
        {/if}
    </span>

    <!-- The count only where there is more than the one mail in progress: a number that is always
         "1" while something runs says nothing the ring has not said. -->
    {#if waiting}
        <span class="text-xs font-medium tabular-nums">{waiting}</span>
    {/if}
</Button>
