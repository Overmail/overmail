<!--
    An answer: its markdown, and the tool calls the agent made while writing it, grouped into
    what ran between two pieces of text.
-->
<script lang="ts">
    import SvelteMarkdown from "@humanspeak/svelte-markdown";
    import ToolCallGroup from "$lib/app/ai/messages/tool-calls/ToolCallGroup.svelte";
    import EntityEmail from "$lib/app/entities/EntityEmail.svelte";
    import EntityLabel from "$lib/app/entities/EntityLabel.svelte";
    import EntityPerson from "$lib/app/entities/EntityPerson.svelte";
    import MarkdownLink from "$lib/app/ai/messages/MarkdownLink.svelte";
    import {agentBlocks} from "$lib/app/ai/messages/agentBlocks";

    let {
        content,
        streaming = false,
    }: {
        content: string;
        /** The answer is still being written, so the parser reuses what it already has. */
        streaming?: boolean;
    } = $props();

    const blocks = $derived(agentBlocks(content));

    // Only the entity elements and the link are ours; everything else keeps the library's
    // renderers, which sanitize urls and leave unknown html alone. The html `label` element is
    // overridden on purpose: inside an answer the tag means the user's label, and a form label
    // has nothing to do here.
    const renderers = {
        link: MarkdownLink,
        html: {
            email: EntityEmail,
            label: EntityLabel,
            person: EntityPerson,
        },
    };
</script>

<!-- The markdown blocks bring no margins of their own here, so the spacing is set once. -->
<div class="flex flex-col gap-2 [&_ol]:list-decimal [&_ol]:pl-5 [&_ul]:list-disc [&_ul]:pl-5
            [&_a]:underline [&_code]:font-mono [&_pre]:overflow-x-auto">
    {#each blocks as block, index (index)}
        {#if block.type === "markdown"}
            <SvelteMarkdown source={block.content} {streaming} {renderers}/>
        {:else}
            <!-- The last block of an answer that is still being written is what runs right now. -->
            <ToolCallGroup calls={block.calls} isRunning={streaming && index === blocks.length - 1}/>
        {/if}
    {/each}
</div>
