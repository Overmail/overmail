<script lang="ts">
    // Everything the agent was asked and everything it answered, as a chat log.
    //
    // A test view, and shaped like one: the prompts are shown in full and unwrapped rather than
    // summarised, because the thing worth looking at is exactly what went over the wire. What it
    // made of the mail lives on the screen proper -- this is the transcript behind that.
    //
    // Also where a run is started from: the agent reads a mail because somebody asked, so the
    // button that asks sits with the log that answers.
    import {Badge} from "$lib/components/ui/badge";
    import {Button} from "$lib/components/ui/button";
    import {ArrowClockwiseIcon, PlayIcon} from "phosphor-svelte";
    import type {AgentMessage, AgentRole} from "./EmailDetailStore.svelte";

    let {log, analysing, onStart}: {
        log: AgentMessage[];
        /** Whether a run is in flight: the button waits for it rather than stacking on it. */
        analysing: boolean;
        /** Runs the agent over the mail. Nothing runs until this is called, see the store. */
        onStart: () => void;
    } = $props();

    // The agent does not run on its own, so the button is a start before it is ever a restart. Read
    // off the log rather than tracked apart from it: a line having been logged is the same thing as
    // a run having happened, and a run that starts empties it again.
    const started = $derived(log.length > 0);

    /** What each role is called, and what it looks like. */
    const ROLES: Record<AgentRole, {label: string; tone: string}> = {
        system: {label: "System", tone: "bg-muted/50 text-muted-foreground"},
        user: {label: "Mail", tone: "bg-muted/30"},
        thinking: {label: "Gedacht", tone: "bg-muted/30 text-muted-foreground italic"},
        assistant: {label: "Modell", tone: "bg-primary/5 ring-1 ring-primary/20"},
        tool_call: {label: "Werkzeug", tone: "bg-primary/10 ring-1 ring-primary/20 font-mono"},
        tool_result: {label: "Antwort", tone: "bg-muted/30 font-mono"},
        error: {label: "Fehler", tone: "bg-destructive/10 text-destructive"}
    };

    /** The box the log scrolls in, so a run that is still going stays in view. */
    let scroller = $state<HTMLDivElement | null>(null);

    // Follows the newest line, which is what makes it read as a log rather than as a list that
    // has to be scrolled after. Keyed on the count: a line is only ever appended.
    $effect(() => {
        log.length;
        scroller?.scrollTo({top: scroller.scrollHeight});
    });

    /** What one request cost, or nothing at all where the backend counted nothing. */
    function cost(message: AgentMessage): string | null {
        const counted = [
            message.input_tokens != null && `${message.input_tokens} rein`,
            message.output_tokens != null && `${message.output_tokens} raus`
        ].filter(Boolean);

        return counted.length ? counted.join(" · ") : null;
    }
</script>

<section class="flex flex-col gap-3 rounded-2xl border p-6">
    <header class="flex flex-wrap items-center gap-2">
        <h2 class="text-sm font-medium">Agent-Log</h2>
        <Badge variant="secondary">Test</Badge>

        <span class="text-muted-foreground text-xs">
            Alles, was der Agent gefragt und geantwortet hat.
        </span>

        <Button
                variant={started ? "outline" : "default"}
                size="sm"
                class="ms-auto"
                disabled={analysing}
                onclick={onStart}
        >
            {#if started}<ArrowClockwiseIcon />{:else}<PlayIcon />{/if}
            {analysing ? "Läuft …" : started ? "Neu starten" : "Agent starten"}
        </Button>
    </header>

    <!-- Capped and scrolled: one run is a couple of thousand characters of prompt, and the mail
         next to it is still the point of the screen. Stacked under the mail on a narrow window it
         gets a fixed cap; in its own column it takes what the viewport has left. -->
    <div
            bind:this={scroller}
            class="flex max-h-96 flex-col gap-2 overflow-y-auto lg:max-h-[calc(100dvh-13rem)]"
    >
        {#each log as message, index (index)}
            <div class="flex flex-col gap-1 rounded-xl p-3 {ROLES[message.role].tone}">
                <div class="flex flex-wrap items-baseline gap-2 text-xs">
                    <span class="font-medium">{ROLES[message.role].label}</span>
                    <span class="text-muted-foreground">
                        {message.step}
                        <!-- Only worth saying once there is more than one: attempt 2 is a step
                             that had to be asked again, which is the interesting case. -->
                        {#if message.attempt > 1}· Versuch {message.attempt}{/if}
                    </span>
                    {#if cost(message)}
                        <span class="text-muted-foreground ms-auto tabular-nums">{cost(message)}</span>
                    {/if}
                </div>

                <pre class="font-mono text-xs whitespace-pre-wrap wrap-anywhere">{message.text}</pre>
            </div>
        {:else}
            <p class="text-muted-foreground text-sm">
                {analysing
                    ? "Der Agent liest die Mail …"
                    : "Der Agent läuft nicht von allein. Starte ihn, um zu sehen, was er aus der Mail liest."}
            </p>
        {/each}
    </div>
</section>
