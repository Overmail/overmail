<!-- The assistant: which chat is open, its messages, and the prompt below them. -->
<script lang="ts">
    import {onMount} from "svelte";
    import {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import AiChatSwitcher from "$lib/app/ai/chats/AiChatSwitcher.svelte";
    import ChatMessageList from "$lib/app/ai/messages/ChatMessageList.svelte";
    import ChatComposer from "$lib/app/ai/composer/ChatComposer.svelte";
    import {useRepositories} from "$lib/repository/repositories";

    const chatViewModel = new AiChatViewModel(useRepositories().chatHistory);

    onMount(() => () => chatViewModel.dispose());

    let composer: ReturnType<typeof ChatComposer> | undefined = $state();

    /** Called by whoever opens the assistant: it is there to be typed into. */
    export function focusPrompt() {
        composer?.focusPrompt();
    }
</script>

<div class="flex flex-col p-4 flex-1">
    <AiChatSwitcher viewModel={chatViewModel} onCloseFocus={focusPrompt}/>

    <div class="w-full flex-1 overflow-y-auto">
        <ChatMessageList viewModel={chatViewModel}/>
    </div>

    <ChatComposer bind:this={composer} viewModel={chatViewModel}/>
</div>
