<!--
    The correspondents a query turns up, as a list to pick from. The sender side of `LabelPicker`,
    and built the same way: everything about picking one and nothing about where the list sits.
    The keyboard is the caller's as well -- whoever owns the input hands key events to [handleKey],
    because that is where they arrive.
-->
<script lang="ts">
    import {CheckIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {OvermailCircularAvatar} from "$lib/components/avatar";
    import {displayName} from "$lib/app/mails/participants";
    import {findSenders, type SenderSearchResult} from "$lib/app/senders/senderSearch";
    import {cn, scrollIntoViewWithin} from "$lib/utils.js";

    let {
        query,
        exclude = [],
        selected = [],
        onSelect,
        onDismiss,
        class: className,
    }: {
        /** What to look for; the list follows it as it changes. */
        query: string;
        /** Ids not worth offering. */
        exclude?: string[];
        /** Ids that are already on: they stay in the list and are ticked, see `LabelPicker`. */
        selected?: string[];
        onSelect: (sender: SenderSearchResult) => void;
        /** What Enter means when there is nothing to pick at all. */
        onDismiss?: () => void;
        /** The list's own box: how tall it may get and what shape its rows are. */
        class?: string;
    } = $props();

    let senders: SenderSearchResult[] = $state([]);
    let highlightedIndex = $state(0);

    // The highlight has to stay visible as it moves, even when the list is taller than the box it
    // is in. Only the list container is scrolled; scrollIntoView would move whatever the list is
    // sitting in instead.
    let listElement: HTMLElement | undefined = $state();
    let itemElements: (HTMLElement | undefined)[] = $state([]);

    $effect(() => {
        const item = itemElements[highlightedIndex];
        if (item && listElement) scrollIntoViewWithin(item, listElement);
    });

    $effect(() => {
        const current = query;
        findSenders(current).then((result) => {
            if (current !== query) return; // stale response
            senders = result;
            highlightedIndex = 0;
        });
    });

    const options = $derived(senders.filter((sender) => !exclude.includes(sender.id)));

    function select(sender: SenderSearchResult | undefined) {
        if (sender === undefined) {
            onDismiss?.();
            return;
        }

        onSelect(sender);
    }

    /** Keyboard from whoever owns the input; true means the event was consumed. */
    export function handleKey(event: KeyboardEvent): boolean {
        if (event.key === "ArrowDown" && options.length > 0) {
            highlightedIndex = (highlightedIndex + 1) % options.length;
            return true;
        }
        if (event.key === "ArrowUp" && options.length > 0) {
            highlightedIndex = (highlightedIndex - 1 + options.length) % options.length;
            return true;
        }
        if (event.key === "Enter" && !event.shiftKey) {
            select(options[highlightedIndex] ?? options[0]);
            return true;
        }
        return false;
    }
</script>

{#if options.length === 0}
    <div class="px-2 py-1.5 text-muted-foreground">{$_('senders.empty')}</div>
{/if}

<div bind:this={listElement} class={cn("max-h-64 overflow-y-auto", className)}>
    {#each options as sender, index (sender.id)}
        <!-- mousedown rather than click, and prevented: the field this list is driven from must
             not lose the caret to it. -->
        <button
                bind:this={itemElements[index]}
                type="button"
                class={cn(
                    "flex w-full items-center gap-2 px-2 py-1.5 text-left",
                    index === highlightedIndex && "bg-accent text-accent-foreground",
                )}
                onmousedown={(event) => {
                    event.preventDefault();
                    select(sender);
                }}
                onmouseenter={() => highlightedIndex = index}
        >
            <OvermailCircularAvatar
                    url={sender.avatarUrl}
                    padding={sender.avatarPadding}
                    name={displayName(sender)}
                    class="size-5 shrink-0"
                    fallbackClass="text-[0.6rem]"
            />
            <!-- The address under the name, because two people share a name more often than an
                 address, and a sender without a name is their address alone. -->
            <span class="flex min-w-0 flex-1 flex-col">
                <span class="truncate">{displayName(sender)}</span>
                {#if sender.name}
                    <span class="truncate text-xs text-muted-foreground">{sender.address}</span>
                {/if}
            </span>
            {#if selected.includes(sender.id)}
                <CheckIcon class="size-3.5 shrink-0"/>
            {/if}
            <span class="ms-auto text-xs text-muted-foreground">
                {$_('senders.emailCount', {values: {count: sender.emailCount}})}
            </span>
        </button>
    {/each}
</div>
