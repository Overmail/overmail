<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {cn, initials, scrollIntoViewWithin} from "$lib/utils.js";
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

    // Das Highlight muss sichtbar bleiben, sobald es sich aendert -- auch wenn die Liste
    // laenger ist als das Fenster. Gescrollt wird ausschliesslich der Listen-Container per
    // scrollTop; scrollIntoView wuerde stattdessen das Fenster/Popover verschieben.
    let listElement: HTMLElement | undefined = $state();
    let itemElements: (HTMLElement | undefined)[] = $state([]);

    $effect(() => {
        const item = itemElements[highlightedIndex];
        if (item && listElement) scrollIntoViewWithin(item, listElement);
    });

    $effect(() => {
        const current = query;
        viewModel.findSenders(current).then((result) => {
            if (current !== query) return; // veraltete Antwort
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
            },
        });
    }

    // Vom PromptInput weitergereichte Tastatur-Events; true = Event verbraucht.
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
                <Avatar.Root size="sm">
                    {#if sender.avatarUrl}
                        <Avatar.Image src={sender.avatarUrl} alt=""/>
                    {/if}
                    <Avatar.Fallback class="text-[0.625rem]">{initials(sender.name ?? sender.address)}</Avatar.Fallback>
                </Avatar.Root>
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
