<!--
    A mail the agent read while writing its answer. Rendered from the <toolcall-read-email>
    element the server writes into the message, see ReadEmailTool.markup.
-->
<script lang="ts">
    import EmailSegment from "$lib/app/ai/EmailSegment.svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";

    let {attributes}: {attributes?: Record<string, string>} = $props();

    const email = $derived({
        id: attributeOf(attributes, "emailId") ?? "",
        subject: attributeOf(attributes, "subject") ?? "",
        // Empty rather than absent when the sender has no picture yet.
        avatarUrl: attributeOf(attributes, "avatarUrl") || null,
    });
</script>

<!-- An element without an id is not a tool call this client knows what to do with. -->
{#if email.id !== ""}<EmailSegment {email}/>{/if}
