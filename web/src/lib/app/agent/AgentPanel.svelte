<script lang="ts" module>
    /**
     * The panel's size once it stands open. Exported because the card animates up to it from
     * nothing, and the panel is held at it throughout so its content is revealed rather than
     * reflowed while that runs.
     *
     * Sized off the viewport rather than off the card: the card shrinks to what is in it, so a
     * percentage width would have nothing to resolve against. Three steps -- the whole screen on a
     * phone, full height and half the width on a tablet, and a window in the corner from a desktop
     * width on.
     *
     * `dvh`, not `vh` and not `svh`: on a phone `vh` is the viewport with the address bar out of
     * the way, so a panel that size runs under the bar, and `svh` is the viewport with the bar
     * shown, so it leaves a strip of page visible once the bar retracts. `dvh` is whatever is on
     * screen right now, which is what fullscreen means here. On a desktop all three are the same.
     */
    export const PANEL_SIZE = `
        h-[100dvh] w-dvw
        md:h-[calc(100dvh-3rem)] md:w-[50vw]
        xl:h-[36rem] xl:max-h-[75dvh] xl:w-[28rem]
    `;
</script>

<script lang="ts">
    // The open state of the card: the shell the chat with the agent is to live in, and until that
    // exists, the two ways of setting it to work on the mailbox at large.
    import {ArrowsClockwiseIcon, SparkleIcon, XIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {agentQueue} from "./AgentQueueStore.svelte";

    let {onClose}: {onClose: () => void} = $props();

    // The socket is the store's, and the panel is one of the two things that want it -- see the fab,
    // which holds a claim of its own so the indicator survives the panel being shut.
    $effect(() => {
        agentQueue.open();
        return () => agentQueue.close();
    });

    /**
     * What the queue is doing, in a sentence.
     *
     * The count includes the mail being read, so "1 wartet" while one is running is right rather
     * than off by one: it is what is still owed, and the mail on the model's desk is still owed.
     */
    const queueNote = $derived.by(() => {
        if (agentQueue.status === "offline") return "Verbindung verloren.";
        if (agentQueue.currentMailId) {
            return agentQueue.pending > 1
                ? `Liest eine Mail, ${agentQueue.pending - 1} warten noch.`
                : "Liest eine Mail.";
        }

        return agentQueue.pending > 0
            ? `${agentQueue.pending} Mails warten.`
            : "Nichts zu tun.";
    });

    /** What the last press came to. Said out loud, because "0 neu" is a real and confusing answer. */
    const answerNote = $derived.by(() => {
        const answer = agentQueue.lastAnswer;
        if (!answer) return null;

        if (answer.asked === 0) return "Keine Mails gefunden, die dafür in Frage kommen.";
        if (answer.queued === 0) return `Alle ${answer.asked} warteten schon.`;
        if (answer.already_waiting > 0) {
            return `${answer.queued} eingereiht, ${answer.already_waiting} warteten schon.`;
        }

        return `${answer.queued} eingereiht.`;
    });
</script>

<div class="flex flex-col {PANEL_SIZE}">
    <header class="flex h-12 shrink-0 items-center justify-between ps-5 pe-2">
        <span class="text-sm font-medium">Overmail AI</span>
        <Button
                variant="ghost"
                size="icon-sm"
                class="rounded-full"
                aria-label="Schließen"
                onclick={onClose}
        >
            <XIcon class="size-4" />
        </Button>
    </header>

    <!-- The chat goes in here. Until it does, the panel is what it can already be: what the agent
         is doing, and the two ways of giving it something to do. -->
    <div class="flex min-h-0 flex-1 flex-col gap-4 px-5 pb-5">
        <p class="text-muted-foreground text-sm">{queueNote}</p>

        <div class="flex flex-col gap-2">
            <Button variant="secondary" class="justify-start" onclick={() => agentQueue.processNewest()}>
                <SparkleIcon class="size-4" />
                Die 10 neuesten Mails lesen
            </Button>

            <Button
                    variant="secondary"
                    class="justify-start"
                    onclick={() => agentQueue.processUnclassified()}
            >
                <ArrowsClockwiseIcon class="size-4" />
                Die 10 neuesten ohne Analyse
            </Button>
        </div>

        {#if answerNote}
            <p class="text-muted-foreground text-xs">{answerNote}</p>
        {/if}
    </div>
</div>
