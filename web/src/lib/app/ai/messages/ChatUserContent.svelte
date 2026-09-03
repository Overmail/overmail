<!-- A sent prompt, rendered the way it looked in the editor. -->
<script lang="ts">
    import EmailSegment from "$lib/app/ai/segments/EmailSegment.svelte";
    import LabelSegment from "$lib/app/ai/segments/LabelSegment.svelte";
    import SenderSegment from "$lib/app/ai/segments/SenderSegment.svelte";
    import type {ChatMessageSegment} from "$lib/app/ai/ChatHistoryRepository";
    import type {PromptEmail, PromptLabel, PromptSender} from "$lib/app/ai/composer/prompt";
    import {_} from "svelte-i18n";

    let {segments}: {segments: ChatMessageSegment[]} = $props();

    /**
     * The chips of the prompt. A reference the server could no longer resolve -- deleted since --
     * keeps its shape and says so instead of showing a blank chip.
     */
    function emailChip(email: {id: string; subject: string | null; avatarUrl: string | null; avatarPadding: number | null}): PromptEmail {
        return {...email, subject: email.subject ?? $_("ai.chat.messages.deletedReference")};
    }

    function labelChip(label: {id: string; name: string | null; color: string | null}): PromptLabel {
        return {
            id: label.id,
            name: label.name ?? $_("ai.chat.messages.deletedReference"),
            color: label.color ?? "currentColor",
        };
    }

    function senderChip(
        sender: {id: string; address: string | null; name: string | null; avatarUrl: string | null; avatarPadding: number | null},
    ): PromptSender {
        return {...sender, address: sender.address ?? $_("ai.chat.messages.deletedReference")};
    }
</script>

<!-- No line breaks between the segments: the bubble keeps whitespace, so every newline in this
     template would show up in the message. -->
{#each segments as segment, index (index)}{#if segment.type === "text"}{segment.content}{:else if segment.type === "email"}<EmailSegment email={emailChip(segment.email)}/>{:else if segment.type === "label"}<LabelSegment label={labelChip(segment.label)}/>{:else if segment.type === "sender"}<SenderSegment sender={senderChip(segment.sender)}/>{/if}{/each}
