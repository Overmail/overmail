<!--
    What the assistant did with its own memory while writing an answer: looked something up, read
    an entry, or wrote one down. Rendered from <toolcall-search-knowledge>,
    <toolcall-read-knowledge> and <toolcall-write-knowledge> -- see KnowledgeTools.kt.

    One component for the three, because the line is the same shape every time: what happened, and
    the words it happened to. Which of them it is decides the icon and the label.
-->
<script lang="ts">
    import {BookOpenTextIcon, BrainIcon, MagnifyingGlassIcon} from "phosphor-svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {_} from "svelte-i18n";

    let {
        kind,
        attributes,
    }: {
        kind: "search" | "read" | "write";
        attributes?: Record<string, string>;
    } = $props();

    /** The search carries what was looked for, the other two the name of the entry. */
    const subject = $derived(
        (kind === "search" ? attributeOf(attributes, "query") : attributeOf(attributes, "name")) ?? ""
    );

    /** A write that landed on an entry that was already there rewrote it rather than adding one. */
    const replaced = $derived(attributeOf(attributes, "replaced") === "true");

    const label = $derived(
        kind === "search"
            ? "ai.chat.messages.searchKnowledge"
            : kind === "read"
                ? "ai.chat.messages.readKnowledge"
                : replaced
                    ? "ai.chat.messages.updateKnowledge"
                    : "ai.chat.messages.writeKnowledge"
    );
</script>

<span class="inline-flex max-w-full items-center gap-1.5 align-[-0.2em] text-muted-foreground">
    {#if kind === "search"}
        <MagnifyingGlassIcon class="size-4 shrink-0"/>
    {:else if kind === "read"}
        <BookOpenTextIcon class="size-4 shrink-0"/>
    {:else}
        <BrainIcon class="size-4 shrink-0"/>
    {/if}

    <span class="shrink-0">{$_(label)}</span>

    <!-- An empty query is the agent asking what is known at all; then there is nothing to show. -->
    {#if subject !== ""}
        <span class="truncate text-foreground" title={subject}>{subject}</span>
    {/if}
</span>
