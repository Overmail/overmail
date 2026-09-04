<!--
    The labels a query turns up, as a list to pick one from.

    Everything about picking a label and nothing about where the list sits: the prompt's trigger
    window puts it above the caret, the mail panel hangs it under a badge, and both hand it a query
    and take back the label that was picked. The keyboard is theirs as well -- whoever owns the
    input hands key events to [handleKey], because that is where they arrive.
-->
<script lang="ts">
    import {PlusIcon, TagIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {cn, scrollIntoViewWithin} from "$lib/utils.js";
    import {findLabels, type LabelSearchResult} from "$lib/app/labels/labelSearch";

    let {
        query,
        exclude = [],
        allowCreationOfNewLabels = false,
        onSelect,
        onCreate,
        onDismiss,
        class: className,
    }: {
        /** What to look for; the list follows it as it changes. */
        query: string;
        /** Ids not worth offering -- the labels a mail already carries, say. */
        exclude?: string[];
        /**
         * Whether making a new one is on the table here. With it, the last row is always
         * "create <what was typed>" -- even next to a label of that name, because the caller
         * asked for the row and not for a guess about it. Without it there is no such row.
         */
        allowCreationOfNewLabels?: boolean;
        onSelect: (label: LabelSearchResult) => void;
        /** What that last row does. */
        onCreate?: (name: string) => void;
        /** What Enter means when there is nothing to pick at all. */
        onDismiss?: () => void;
        /**
         * The list's own box: how tall it may get, what it leaves room for, and what shape its
         * rows are -- `*:rounded-2xl` for a menu-sized popover, `*:rounded-sm` for the prompt's
         * window. A row brings no radius of its own, because the right one is the one the box
         * around it has minus its padding.
         */
        class?: string;
    } = $props();

    let labels: LabelSearchResult[] = $state([]);
    let highlightedIndex = $state(0);

    // The highlight has to stay visible as it moves, even when the list is taller than the box it
    // is in. Only the list container is scrolled, via scrollTop; scrollIntoView would move
    // whatever the list is sitting in instead.
    let listElement: HTMLElement | undefined = $state();
    let itemElements: (HTMLElement | undefined)[] = $state([]);

    $effect(() => {
        const item = itemElements[highlightedIndex];
        if (item && listElement) scrollIntoViewWithin(item, listElement);
    });

    $effect(() => {
        const current = query;
        findLabels(current).then((result) => {
            if (current !== query) return; // stale response
            labels = result;
            highlightedIndex = 0;
        });
    });

    type Option = {type: "label"; label: LabelSearchResult} | {type: "create"};

    const options: Option[] = $derived.by(() => {
        const opts: Option[] = labels
            .filter((label) => !exclude.includes(label.id))
            .map((label) => ({type: "label" as const, label}));

        // Last, and last on purpose: whoever is typing a name that does not exist yet ends up
        // on it by pressing ArrowUp once. An empty query names nothing, so there is nothing to
        // create either.
        if (allowCreationOfNewLabels && query.trim() !== "") opts.push({type: "create"});

        return opts;
    });

    function select(option: Option | undefined) {
        if (option === undefined) {
            onDismiss?.();
            return;
        }

        if (option.type === "create") {
            onCreate?.(query.trim());
            return;
        }

        onSelect(option.label);
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
    <div class="px-2 py-1.5 text-muted-foreground">{$_('labels.empty')}</div>
{/if}

<div bind:this={listElement} class={cn("max-h-64 overflow-y-auto", className)}>
    {#each options as option, index}
        <!-- mousedown rather than click: in the prompt the editor must not lose the caret to
             this, and preventDefault is what keeps it. -->
        <button
                bind:this={itemElements[index]}
                type="button"
                class={cn(
                    "flex w-full items-center gap-2 px-2 py-1.5 text-left",
                    index === highlightedIndex && "bg-accent text-accent-foreground",
                )}
                onmousedown={(event) => {
                    event.preventDefault();
                    select(option);
                }}
                onmouseenter={() => highlightedIndex = index}
        >
            {#if option.type === "label"}
                <TagIcon class="size-3.5 shrink-0" color={option.label.color}/>
                <span class="truncate flex-1">{option.label.name}</span>
                <span class="ms-auto text-xs text-muted-foreground">
                    {$_('labels.emailCount', {values: {count: option.label.emailCount}})}
                </span>
            {:else}
                <PlusIcon class="size-3.5 shrink-0"/>
                <span class="truncate">{$_('labels.create', {values: {name: query.trim()}})}</span>
            {/if}
        </button>
    {/each}
</div>
