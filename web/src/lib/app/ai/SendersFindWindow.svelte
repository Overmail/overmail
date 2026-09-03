<script lang="ts">
    import {OvermailAvatar} from "$lib/components/avatar";
    import {cn, scrollIntoViewWithin} from "$lib/utils.js";
    import TriggerWindow from "./TriggerWindow.svelte";
    import type {PromptTriggerWindowProps} from "./prompt";
    import type {SenderSearchResult} from "./OvermailPromptViewModel.svelte";
    import {_} from "svelte-i18n";

    let {
        query,
        left,
        bottom,
        viewModel,
        onReplace,
        onDismiss,
    }: PromptTriggerWindowProps = $props();

    let senders: SenderSearchResult[] = $state([]);
    let highlightedIndex = $state(0);

    // The highlight has to stay visible as it moves, even when the list is taller than the
    // window. Only the list container is scrolled, via scrollTop; scrollIntoView would move
    // the window or the popover instead.
    let listElement: HTMLElement | undefined = $state();
    let itemElements: (HTMLElement | undefined)[] = $state([]);

    $effect(() => {
        const item = itemElements[highlightedIndex];
        if (item && listElement) scrollIntoViewWithin(item, listElement);
    });

    $effect(() => {
        const current = query;
        viewModel.findSenders(current).then((result) => {
            if (current !== query) return; // stale response
            senders = result;
            highlightedIndex = 0;
        });
    });

    function select(sender: SenderSearchResult | undefined) {
        if (!sender) {
            onDismiss();
            return;
        }

        onReplace({
            type: "sender",
            sender: {
                id: sender.id,
                name: sender.name,
                address: sender.address,
                avatarUrl: sender.avatarUrl,
                avatarPadding: sender.avatarPadding,
            },
        });
    }

    // Keyboard events handed down by PromptInput; true means the event was consumed.
    export function handleKey(event: KeyboardEvent): boolean {
        if (event.key === "ArrowDown" && senders.length > 0) {
            highlightedIndex = (highlightedIndex + 1) % senders.length;
            return true;
        }
        if (event.key === "ArrowUp" && senders.length > 0) {
            highlightedIndex = (highlightedIndex - 1 + senders.length) % senders.length;
            return true;
        }
        if (event.key === "Enter" && !event.shiftKey) {
            select(senders[highlightedIndex]);
            return true;
        }
        return false;
    }
</script>

<TriggerWindow {left} {bottom} class="w-max min-w-72 max-w-[min(24rem,calc(100vw-2rem))] p-1">
    {#if senders.length === 0}
        <div class="px-2 py-1.5 text-muted-foreground">{$_('ai.senders.empty')}</div>
    {/if}

    <div bind:this={listElement} class="max-h-64 overflow-y-auto pb-12">
        {#each senders as sender, index}
            <button
                    bind:this={itemElements[index]}
                    type="button"
                    class={cn(
                        "flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left",
                        index === highlightedIndex && "bg-accent text-accent-foreground",
                    )}
                    onmousedown={(event) => {
                        event.preventDefault();
                        select(sender);
                    }}
                    onmouseenter={() => highlightedIndex = index}
            >
                <OvermailAvatar
                        size="sm"
                        url={sender.avatarUrl}
                        name={sender.name ?? sender.address}
                />
                <span class="flex min-w-0 flex-1 flex-col">
                    <span class="truncate">{sender.name ?? sender.address}</span>
                    {#if sender.name}
                        <span class="truncate text-xs text-muted-foreground">{sender.address}</span>
                    {/if}
                </span>
                <span class="ms-auto shrink-0 text-xs text-muted-foreground">
                    {$_('ai.senders.emailCount', {values: {count: sender.emailCount}})}
                </span>
            </button>
        {/each}
    </div>
</TriggerWindow>
