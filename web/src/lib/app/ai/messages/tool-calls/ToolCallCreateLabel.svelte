<!--
    A label the agent made while writing its answer. Rendered from the <toolcall-create-label>
    element the server writes into the message, see CreateLabelTool.markup.

    Only the id is in the element; what the label looks like is looked up, so a label that was
    renamed since does not keep its old name in an old message.
-->
<script lang="ts">
    import {TagIcon} from "phosphor-svelte";
    import EntityLabel from "$lib/app/entities/EntityLabel.svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {_} from "svelte-i18n";

    let {attributes}: {attributes?: Record<string, string>} = $props();

    const labelId = $derived(attributeOf(attributes, "labelId") ?? "");
</script>

<!-- An element without an id is not a tool call this client knows what to do with. -->
{#if labelId !== ""}
        <!-- flex-wrap: the chips are one line each and cannot be cut, so a narrow panel gets a
         second line rather than a row that reaches past its edge. -->
    <span class="inline-flex max-w-full flex-wrap items-center gap-1.5 align-[-0.2em] text-muted-foreground">
        <TagIcon class="size-4 shrink-0"/>
        <span class="shrink-0">{$_("ai.chat.messages.createLabel")}</span>
        <EntityLabel id={labelId}/>
    </span>
{/if}
