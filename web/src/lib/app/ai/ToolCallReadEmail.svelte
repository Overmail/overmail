<!--
    A mail the agent read while writing its answer. Rendered from the <toolcall-read-email>
    element the server writes into the message, see ReadEmailTool.markup.
-->
<script lang="ts">
    import {EyeglassesIcon} from "phosphor-svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {_} from "svelte-i18n";

    let {attributes}: {attributes?: Record<string, string>} = $props();

    const email = $derived({
        id: attributeOf(attributes, "emailId") ?? "",
        subject: attributeOf(attributes, "subject") ?? "",
        // Empty rather than absent when the sender has no picture yet.
        avatarUrl: attributeOf(attributes, "avatarUrl") || null,
    });
</script>

<!-- An element without an id is not a tool call this client knows what to do with. -->
{#if email.id !== ""}
    <span class="inline-flex max-w-full items-center gap-1.5 align-[-0.2em] text-muted-foreground">
        <EyeglassesIcon class="size-4 shrink-0"/>
        <span class="shrink-0">{$_("ai.chat.messages.readEmail")}</span>
        {#if email.avatarUrl}
            <img src={email.avatarUrl} alt="" class="size-4 shrink-0 rounded-full object-cover"/>
        {/if}
        <span class="truncate text-foreground">{email.subject}</span>
    </span>
{/if}
