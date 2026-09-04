<!-- The assistant: which chat is open, its messages, and the prompt below them. -->
<script lang="ts">
    import {onMount, tick} from "svelte";
    import {fade} from "svelte/transition";
    import {ArrowDownIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import AiChatSwitcher from "$lib/app/ai/chats/AiChatSwitcher.svelte";
    import ChatMessageList from "$lib/app/ai/messages/ChatMessageList.svelte";
    import ChatComposer from "$lib/app/ai/composer/ChatComposer.svelte";
    import {isAtBottom} from "$lib/app/ai/messages/chatScroll";
    import {Button} from "$lib/components/ui/button";
    import {useRepositories} from "$lib/repository/repositories";

    const chatViewModel = new AiChatViewModel(useRepositories().chatHistory);

    onMount(() => () => chatViewModel.dispose());

    let composer: ReturnType<typeof ChatComposer> | undefined = $state();

    /** Called by whoever opens the assistant: it is there to be typed into. */
    export function focusPrompt() {
        composer?.focusPrompt();
    }

    let scrollBox: HTMLElement | undefined = $state();
    let content: HTMLElement | undefined = $state();

    /**
     * Whether the list follows the end of the conversation.
     *
     * It is the reader's scroll position that says so, and nothing else: scrolling up gives the
     * following up, scrolling back down to the end takes it back, and so does the button. That
     * way an answer being written pushes the view along for somebody who is reading the end, and
     * leaves alone whoever went back to read something further up.
     */
    let followsEnd = $state(true);

    function scrollToEnd(behavior: ScrollBehavior) {
        scrollBox?.scrollTo({top: scrollBox.scrollHeight, behavior});
    }

    // An answer grows token by token and a new message is one more child of the list: what to
    // follow is the height of the content, not any one of the things that change it.
    $effect(() => {
        const box = scrollBox;
        const inner = content;
        if (!box || !inner) return;

        const observer = new ResizeObserver(() => {
            // Read when the height changed, not while this effect runs, so the observer is not
            // rebuilt every time the flag flips.
            if (followsEnd) scrollToEnd("auto");
        });
        observer.observe(inner);

        return () => observer.disconnect();
    });

    // Another chat starts at its end, whatever the one before it was scrolled to. The messages
    // may already be here, in which case nothing changes height and the observer above says
    // nothing -- so this scrolls itself once the switch is rendered.
    $effect(() => {
        void chatViewModel.currentChatId;

        followsEnd = true;
        void tick().then(() => scrollToEnd("auto"));
    });
</script>

<div class="flex min-h-0 min-w-0 flex-1 flex-col p-4">
    <AiChatSwitcher viewModel={chatViewModel} onCloseFocus={focusPrompt}/>

    <!-- relative, so the button below sits over the end of the list rather than in it: inside
         the scroll box it would scroll away with the messages. -->
    <div class="relative flex min-h-0 w-full min-w-0 flex-1 flex-col">
        <!--
            min-h-0: a flex item is as tall as its content unless it is told it may be shorter,
            and without that this box grows instead of scrolling.

            overflow-x-hidden, and min-w-0 down the chain above: the panel is a column of text
            and never scrolls sideways. What is genuinely wider than it -- a code block, a table
            -- scrolls inside its own box instead, see ChatAgentContent.
        -->
        <div
                bind:this={scrollBox}
                onscroll={() => followsEnd = scrollBox !== undefined && isAtBottom(scrollBox)}
                class="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto"
        >
            <div bind:this={content} class="min-w-0">
                <ChatMessageList viewModel={chatViewModel}/>
            </div>
        </div>

        {#if !followsEnd}
            <div
                    transition:fade={{duration: 120}}
                    class="pointer-events-none absolute inset-x-0 bottom-2 flex justify-center"
            >
                <Button
                        variant="outline"
                        size="icon-sm"
                        class="pointer-events-auto rounded-full shadow-md"
                        onclick={() => {
                            followsEnd = true;
                            scrollToEnd("smooth");
                        }}
                >
                    <ArrowDownIcon/>
                    <span class="sr-only">{$_('ai.chat.scrollToEnd')}</span>
                </Button>
            </div>
        {/if}
    </div>

    <ChatComposer bind:this={composer} viewModel={chatViewModel}/>
</div>
