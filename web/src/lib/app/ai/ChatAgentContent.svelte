<!-- An answer: its text, and a chip for every mail the agent read while writing it. -->
<script lang="ts">
    import EmailSegment from "$lib/app/ai/EmailSegment.svelte";
    import {parseAgentMessage} from "$lib/app/ai/agentMessage";

    let {content}: {content: string} = $props();

    const parts = $derived(parseAgentMessage(content));
</script>

<div class="flex flex-col gap-2">
    {#each parts as part, index (index)}
        {#if part.type === "text"}
            <span class="whitespace-pre-wrap break-words">{part.content}</span>
        {:else}
            <span><EmailSegment email={part.email}/></span>
        {/if}
    {/each}
</div>
