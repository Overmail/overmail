<!-- One message: the bubble, when it was sent, and what can be done with it. -->
<script lang="ts">
    import ChatUserContent from "$lib/app/ai/messages/ChatUserContent.svelte";
    import ChatAgentContent from "$lib/app/ai/messages/ChatAgentContent.svelte";
    import ChatMessageActions from "$lib/app/ai/messages/ChatMessageActions.svelte";
    import RelativeTime from "$lib/components/time/RelativeTime.svelte";
    import {Spinner} from "$lib/components/ui/spinner";
    import type {AiChatMessage} from "$lib/app/ai/ChatHistoryRepository";
    import {_} from "svelte-i18n";

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

<li class="flex min-w-0 flex-col gap-0.5" class:items-end={isUser}>
    <!-- 80% keeps the two sides apart even when a message is one long line. -->
    <div
            class="min-w-0 max-w-[80%] rounded-lg py-2 text-sm wrap-break-word"
            class:whitespace-pre-wrap={isUser}
            class:px-3={isUser}
            class:bg-muted={isUser}
    >
        {#if message.type === "user"}
            <ChatUserContent segments={message.content}/>
        {:else if message.content !== ""}
            <ChatAgentContent content={message.content} streaming={message.pending}/>
        {:else}
            <!-- Nothing streamed yet: the answer is queued or just starting. -->
            <Spinner class="size-4"/>
        {/if}
    </div>

    {#if !isUser}
        <div class="flex flex-col items-start gap-1 text-muted-foreground text-xs">
            {#if hasActions}
                <div class="mb-2">
                    <ChatMessageActions disabled={isBusy} onRetry={() => onRetry(message.id)}/>
                </div>
            {/if}

            <div class="flex items-center gap-1.5">
                <RelativeTime date={message.created_at}/>
                {#if message.type === "assistant" && message.tokensOutput > 0}
                    <span aria-hidden="true">·</span>
                    <span>{$_("ai.chat.messages.tokensOutput", {values: {count: message.tokensOutput}})}</span>
                {/if}
            </div>
        </div>
    {/if}
</li>
