<!-- One message: the bubble, when it was sent, and what can be done with it. -->
<script lang="ts">
    import ChatUserContent from "$lib/app/ai/ChatUserContent.svelte";
    import ChatAgentContent from "$lib/app/ai/ChatAgentContent.svelte";
    import ChatMessageActions from "$lib/app/ai/ChatMessageActions.svelte";
    import RelativeTime from "$lib/components/time/RelativeTime.svelte";
    import {Spinner} from "$lib/components/ui/spinner";
    import type {AiChatMessage} from "$lib/app/ai/ChatHistoryRepository";

    let {
        message,
        isBusy = false,
        onRetry,
    }: {
        message: AiChatMessage;
        /** An answer is running in this chat; retrying now would collide with it. */
        isBusy?: boolean;
        onRetry: (messageId: string) => void;
    } = $props();

    const isUser = $derived(message.type === "user");

    // Only a finished answer with something in it can be acted on: there is nothing to retry
    // while the tokens are still coming in.
    const hasActions = $derived(message.type === "assistant" && !message.pending && message.content !== "");
</script>

<li class="flex flex-col gap-0.5" class:items-end={isUser}>
    <!-- 80% keeps the two sides apart even when a message is one long line. -->
    <div
            class="max-w-[80%] rounded-lg px-3 py-2 text-sm break-words"
            class:whitespace-pre-wrap={isUser}
            class:bg-primary={isUser}
            class:text-primary-foreground={isUser}
            class:bg-muted={!isUser}
    >
        {#if message.type === "user"}
            <ChatUserContent segments={message.content}/>
        {:else if message.content !== ""}
            <ChatAgentContent content={message.content}/>
        {:else}
            <!-- Nothing streamed yet: the answer is queued or just starting. -->
            <Spinner class="size-4"/>
        {/if}
    </div>

    <div class="flex items-center gap-1 text-muted-foreground text-xs">
        <RelativeTime date={message.created_at}/>
        {#if hasActions}
            <ChatMessageActions disabled={isBusy} onRetry={() => onRetry(message.id)}/>
        {/if}
    </div>
</li>
