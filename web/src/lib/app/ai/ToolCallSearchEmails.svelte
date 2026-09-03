<!--
    A search the agent ran while writing its answer. Rendered from the <toolcall-search-emails>
    element the server writes into the message, see SearchEmailsTool.markup.
-->
<script lang="ts">
    import {MagnifyingGlassIcon} from "phosphor-svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {_} from "svelte-i18n";

    let {attributes}: {attributes?: Record<string, string>} = $props();

    // An argument the agent left out is empty in the markup and is not shown at all.
    const subject = $derived(attributeOf(attributes, "subject") ?? "");
    const sender = $derived(attributeOf(attributes, "sender") ?? "");
</script>

<span class="inline-flex max-w-full items-center gap-1.5 align-[-0.2em] text-muted-foreground">
    <MagnifyingGlassIcon class="size-4 shrink-0"/>
    <span class="shrink-0">{$_("ai.chat.messages.searchEmails")}</span>
    {#if subject !== ""}
        <span class="truncate">{$_("ai.chat.messages.searchSubject", {values: {query: subject}})}</span>
    {/if}
    {#if sender !== ""}
        <span class="truncate">{$_("ai.chat.messages.searchSender", {values: {query: sender}})}</span>
    {/if}
</span>
