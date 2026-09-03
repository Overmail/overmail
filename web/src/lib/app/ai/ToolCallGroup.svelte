<!--
    The tool calls that ran between two pieces of an answer, as one collapsible group.

    Collapsed it is a single line: while the group is the last thing in the answer, the orb of
    the call that is running; once the answer went on, a wrench and how many calls it took.
-->
<script lang="ts">
    import {CaretRightIcon, WrenchIcon} from "phosphor-svelte";
    import {fly, slide} from "svelte/transition";
    import ThinkingOrb from "$lib/components/orb/ThinkingOrb.svelte";
    import ToolCallReadEmail from "$lib/app/ai/ToolCallReadEmail.svelte";
    import ToolCallSearchEmails from "$lib/app/ai/ToolCallSearchEmails.svelte";
    import ToolCallThinking from "$lib/app/ai/ToolCallThinking.svelte";
    import type {ToolCall} from "$lib/app/ai/agentBlocks";
    import type {OrbState} from "thinking-orbs/engine";
    import {_} from "svelte-i18n";

    let {
        calls,
        isRunning = false,
    }: {
        calls: ToolCall[];
        /** Nothing follows this group yet, so its last call is what the agent is doing now. */
        isRunning?: boolean;
    } = $props();

    let isExpanded = $state(false);

    /** An orb per kind of work, so the line says what is going on without reading it. */
    const ORBS: Record<string, OrbState> = {
        "search-emails": "searching",
        "read-email": "working",
        thinking: "breathing",
    };

    const LABELS: Record<string, string> = {
        "search-emails": "ai.chat.messages.searchEmails",
        "read-email": "ai.chat.messages.readEmail",
        thinking: "ai.chat.messages.thinking",
    };

    const current = $derived(calls[calls.length - 1]);

    // One line of text, whatever the group is doing: what runs right now, or what it took. It
    // changes while the agent works, and the change is what the roll below animates.
    const label = $derived(
        isRunning
            ? $_(LABELS[current.kind] ?? "ai.chat.messages.toolCall")
            : $_("ai.chat.messages.toolCalls", {values: {count: calls.length}}),
    );
</script>

<div class="text-sm">
    <button
            type="button"
            class="flex w-full items-center gap-2 py-1 text-left text-muted-foreground
                   transition-colors hover:text-foreground"
            aria-expanded={isExpanded}
            onclick={() => isExpanded = !isExpanded}
    >
        {#if isRunning}
            <ThinkingOrb variant={ORBS[current.kind] ?? "working"} size={20} class="shrink-0"/>
        {:else}
            <WrenchIcon class="size-5 shrink-0 p-0.5"/>
        {/if}

        <!-- Fixed height, stacked and clipped: the two lines pass each other inside the row
             instead of pushing it apart or floating over it. -->
        <span class="relative h-5 min-w-0 flex-1 overflow-hidden">
            {#key label}
                <span
                        class="absolute inset-0 truncate"
                        in:fly={{y: 12, duration: 220}}
                        out:fly={{y: -12, duration: 220}}
                >
                    {label}
                </span>
            {/key}
        </span>

        <CaretRightIcon
                class="size-3.5 shrink-0 transition-transform duration-200 {isExpanded ? 'rotate-90' : ''}"
        />
    </button>

    {#if isExpanded}
        <!-- Indented to where the label starts, so the rows read as its detail. -->
        <ul class="flex flex-col gap-1.5 py-1 ps-7" transition:slide={{duration: 200}}>
            {#each calls as call, index (index)}
                <li>
                    {#if call.kind === "thinking"}
                        <ToolCallThinking content={call.content}/>
                    {:else if call.kind === "read-email"}
                        <ToolCallReadEmail attributes={call.attributes}/>
                    {:else if call.kind === "search-emails"}
                        <ToolCallSearchEmails attributes={call.attributes}/>
                    {/if}
                </li>
            {/each}
        </ul>
    {/if}
</div>
