<!--
    A chat the agent renamed while writing its answer. Rendered from the <toolcall-rename-chat>
    element the server writes into the message, see RenameChatTool.markup.

    The name is in the element rather than looked up: unlike a label, this line is about what the
    chat was called at that point, and a later rename does not change what happened here.
-->
<script lang="ts">
    import {PencilSimpleIcon} from "phosphor-svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {_} from "svelte-i18n";

    let {attributes}: {attributes?: Record<string, string>} = $props();

    const name = $derived(attributeOf(attributes, "name") ?? "");
</script>

{#if name !== ""}
    <span class="inline-flex max-w-full items-center gap-1.5 align-[-0.2em] text-muted-foreground">
        <PencilSimpleIcon class="size-4 shrink-0"/>
        <span class="shrink-0">{$_("ai.chat.messages.renameChat")}</span>
        <span class="truncate text-foreground" title={name}>{name}</span>
    </span>
{/if}
