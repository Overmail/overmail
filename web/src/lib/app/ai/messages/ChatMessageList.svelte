<!-- The open chat, oldest message first. -->
<script lang="ts">
    import ChatMessage from "$lib/app/ai/messages/ChatMessage.svelte";
    import type {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import {_} from "svelte-i18n";

    let {viewModel}: {viewModel: AiChatViewModel} = $props();
</script>

<h2 class="text-muted-foreground text-sm font-medium tracking-tight">
    {$_('ai.chat.history')}
</h2>

<ul class="flex flex-col gap-3 py-3">
    {#each viewModel.currentChatMessages as message (message.id)}
        <ChatMessage
                {message}
                isBusy={viewModel.isAnswering}
                onRetry={(messageId) => viewModel.retryMessage(messageId)}
        />
    {/each}
</ul>
