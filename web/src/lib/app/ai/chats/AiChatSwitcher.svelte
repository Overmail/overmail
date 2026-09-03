<script lang="ts">
    import * as Popover from "$lib/components/ui/popover";
    import {Button} from "$lib/components/ui/button";
    import {CaretDownIcon, PlusIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import AiChatList from "$lib/app/ai/chats/AiChatList.svelte";
    import type {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";
    import {fly, scale} from "svelte/transition";
    import {cubicOut} from "svelte/easing";

    let {
        viewModel,
        onCloseFocus,
    }: {
        viewModel: AiChatViewModel;
        /**
         * Where the keyboard goes once the list closes. Without it the focus stays on the title,
         * which is a button and answers to Space itself instead of letting it through.
         */
        onCloseFocus?: () => void;
    } = $props();

    let open = $state(false);

    let list: ReturnType<typeof AiChatList> | undefined = $state();

    // Focus sits on the listbox, not on a row -- that is what makes arrow keys and typeahead
    // work across rows the windowing never rendered.
    $effect(() => {
        if (open) list?.focusList();
    });

    async function selectChat(chatId: string) {
        open = false;
        await viewModel.onChatSelected(chatId);
    }
</script>

<div class="flex flex-row items-center gap-1">
    <Popover.Root bind:open>
        <Popover.Trigger>
            {#snippet child({props})}
                <!-- pl-4.5: the title lines up with the text edge of the entries that open
                     right below it (6px row indent + 12px inside the entry). -->
                <button
                        {...props}
                        class="flex min-w-0 flex-1 items-center gap-2 rounded-3xl border border-transparent
                               bg-transparent py-1.5 pl-4.5 pr-3 text-start outline-none
                               transition-[color,box-shadow,background-color]
                               hover:bg-input/50 focus-visible:ring-3
                               focus-visible:ring-ring/30"
                >
                    <h1 class="min-w-0 flex-1 truncate text-lg">
                        {viewModel.currentChat
                            ? (viewModel.currentChat.name ?? $_('ai.chat.chats.untitled'))
                            : $_('ai.chat.chats.new')}
                    </h1>
                    <CaretDownIcon class="size-4 shrink-0 text-muted-foreground"/>
                </button>
            {/snippet}
        </Popover.Trigger>

        <!-- The popover ships w-72, m-2, p-4 and gap-4 of its own; here the padding comes from
             the list. The width is pinned to the trigger, so a long chat name cannot inflate
             it. animate-none! kills the css animation the base content brings, like every other
             popover surface in the design system does -- forceMount hands the mounting to us
             instead, so the transition below is the only thing that animates. -->
        <Popover.Content
                side="bottom"
                align="start"
                forceMount
                onCloseAutoFocus={(event) => {
                    if (!onCloseFocus) return;

                    event.preventDefault();
                    onCloseFocus();
                }}
                class="m-0 w-(--bits-popover-anchor-width) gap-0 overflow-hidden p-0 animate-none!"
        >
            {#snippet child({props, wrapperProps, open: contentOpen})}
                {#if contentOpen}
                    <!-- The wrapper is what floating-ui positions; the transform of the
                         transition has to sit on the element inside it. -->
                    <div {...wrapperProps}>
                        <div
                                {...props}
                                transition:fly={{y: -4, duration: 120, easing: cubicOut}}
                        >
                            <AiChatList bind:this={list} {viewModel} onSelect={selectChat}/>
                        </div>
                    </div>
                {/if}
            {/snippet}
        </Popover.Content>
    </Popover.Root>

    {#if viewModel.currentChatId}
        <!-- The button only exists while a chat is open, so it gets the same short fade as the
             list rather than popping in next to the title. -->
        <div transition:scale={{start: 0.8, duration: 120, easing: cubicOut}}>
            <Button
                    variant="ghost"
                    size="icon"
                    onclick={() => viewModel.currentChatId = null}
            >
                <PlusIcon/>
                <span class="sr-only">{$_('ai.chat.chats.new')}</span>
            </Button>
        </div>
    {/if}
</div>
