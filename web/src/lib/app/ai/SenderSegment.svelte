<script lang="ts">
    import {UserIcon} from "phosphor-svelte";
    import type {PromptSender} from "./prompt";

    let {
        sender,
    }: {
        sender: PromptSender;
    } = $props();
</script>

<span class="inline whitespace-nowrap bg-card text-card-foreground px-1 mx-0.5 rounded outline">
    {#if sender.avatarUrl}
        <img src={sender.avatarUrl} alt="" class="inline size-3.5 rounded-full object-cover align-[-0.2em]"/>
    {:else}
        <UserIcon class="inline size-3.5 align-[-0.2em]"/>
    {/if}
    {sender.name ?? sender.address}
</span>
