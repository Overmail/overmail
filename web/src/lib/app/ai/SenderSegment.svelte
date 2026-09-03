<script lang="ts">
    import {UserIcon} from "phosphor-svelte";
    import {OvermailAvatar} from "$lib/components/avatar";
    import type {PromptSender} from "./prompt";

    let {
        sender,
    }: {
        sender: PromptSender;
    } = $props();
</script>

<span class="inline whitespace-nowrap bg-card text-card-foreground px-1 mx-0.5 rounded outline">
    <OvermailAvatar
            inline
            url={sender.avatarUrl}
            class="size-3.5"
    >
        {#snippet fallback()}<UserIcon class="size-full"/>{/snippet}
    </OvermailAvatar>
    {sender.name ?? sender.address}
</span>
