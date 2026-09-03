<!-- What can be done with a finished answer. The buttons fade in one after the other, so they
     read as a consequence of the answer rather than as part of it. -->
<script lang="ts">
    import {ArrowClockwiseIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {fly} from "svelte/transition";
    import {_} from "svelte-i18n";

    let {
        onRetry,
        disabled = false,
    }: {
        onRetry: () => void;
        disabled?: boolean;
    } = $props();

    /** Milliseconds between two buttons appearing. */
    const STAGGER = 60;

    const actions = $derived([
        {key: "retry", label: $_("ai.chat.messages.retry"), icon: ArrowClockwiseIcon, run: onRetry},
    ]);
</script>

<div class="flex items-center gap-0.5">
    {#each actions as action, index (action.key)}
        <div in:fly={{y: 4, duration: 150, delay: index * STAGGER}}>
            <Button
                    variant="ghost"
                    size="icon"
                    class="size-7 text-muted-foreground"
                    title={action.label}
                    {disabled}
                    onclick={action.run}
            >
                <action.icon class="size-4"/>
                <span class="sr-only">{action.label}</span>
            </Button>
        </div>
    {/each}
</div>
