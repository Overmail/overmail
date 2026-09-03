<!-- The assistant: which chat is open, its messages, and the prompt below them. -->
<script lang="ts">
    import {onMount} from "svelte";
    import {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import AiChatSwitcher from "$lib/app/ai/chats/AiChatSwitcher.svelte";
    import ChatMessageList from "$lib/app/ai/messages/ChatMessageList.svelte";
    import ChatComposer from "$lib/app/ai/composer/ChatComposer.svelte";

    const chatViewModel = new AiChatViewModel();

    onMount(() => () => chatViewModel.dispose());

    let composer: ReturnType<typeof ChatComposer> | undefined = $state();

    /** Called by whoever opens the assistant: it is there to be typed into. */
    export function focusPrompt() {
        composer?.focusPrompt();
    }
</script>

<AiChatSwitcher viewModel={chatViewModel} onCloseFocus={focusPrompt}/>

<div class="flex flex-col h-192">
    <div class="w-full flex-1 overflow-y-auto">
        <ChatMessageList viewModel={chatViewModel}/>
    </div>

    <ChatComposer bind:this={composer} viewModel={chatViewModel}/>
</div>
