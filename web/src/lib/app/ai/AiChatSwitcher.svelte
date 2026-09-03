<script lang="ts">
    import * as Popover from "$lib/components/ui/popover";
    import {Button} from "$lib/components/ui/button";
    import {CaretDownIcon, PlusIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import AiChatList from "$lib/app/ai/AiChatList.svelte";
    import type {AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";

    let {viewModel}: {viewModel: AiChatViewModel} = $props();

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
                               hover:bg-input/50 focus-visible:border-ring focus-visible:ring-3
                               focus-visible:ring-ring/30"
                >
                    <h1 class="min-w-0 flex-1 truncate text-xl">
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
             it. -->
        <Popover.Content
                side="bottom"
                align="start"
                class="m-0 w-(--bits-popover-anchor-width) gap-0 overflow-hidden p-0 animate-none!"
        >
            <AiChatList bind:this={list} {viewModel} onSelect={selectChat}/>
        </Popover.Content>
    </Popover.Root>

    {#if viewModel.currentChatId}
        <Button
                variant="ghost"
                size="icon"
                onclick={() => viewModel.currentChatId = null}
        >
            <PlusIcon/>
            <span class="sr-only">{$_('ai.chat.chats.new')}</span>
        </Button>
    {/if}
</div>
