<script lang="ts">
    import {PlusIcon, TagIcon} from "phosphor-svelte";
    import {cn} from "$lib/utils.js";
    import TriggerWindow from "./TriggerWindow.svelte";
    import type {PromptTriggerWindowProps} from "./prompt";
    import type {LabelSearchResult} from "./OvermailPromptViewModel.svelte";

    let {
        query,
        left,
        bottom,
        viewModel,
        onReplace,
        onDismiss,
    }: PromptTriggerWindowProps = $props();

    let labels: LabelSearchResult[] = $state([]);
    let highlightedIndex = $state(0);

    $effect(() => {
        const current = query;
        viewModel.findLabels(current).then((result) => {
            if (current !== query) return; // veraltete Antwort
            labels = result;
            highlightedIndex = 0;
        });
    });

    type Option = {type: "label"; label: LabelSearchResult} | {type: "create"};

    let options: Option[] = $derived.by(() => {
        const opts: Option[] = labels.map((label) => ({type: "label" as const, label}));
        const trimmed = query.trim();
        const exact = labels.some((l) => l.name.toLowerCase() === trimmed.toLowerCase());
        if (trimmed !== "" && !exact) opts.push({type: "create"});
        return opts;
    });

    function select(option: Option | undefined) {
        if (!option || option.type === "create") {
            // Label-Anlage gibt es noch nicht; Enter schließt nur das Fenster.
            onDismiss();
            return;
        }

        onReplace({
            type: "label",
            label: {id: option.label.id, name: option.label.name, color: option.label.color},
        });
    }

    // Vom PromptInput weitergereichte Tastatur-Events; true = Event verbraucht.
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

<TriggerWindow {left} {bottom} class="w-64 p-1">
    {#if options.length === 0}
        <div class="px-2 py-1.5 text-muted-foreground">Keine Labels gefunden</div>
    {/if}

    {#each options as option, index}
        <button
                type="button"
                class={cn(
                    "flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left",
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
                <span class="truncate">{option.label.name}</span>
                <span class="ms-auto text-xs text-muted-foreground">{option.label.emailCount}x</span>
            {:else}
                <PlusIcon class="size-3.5 shrink-0"/>
                <span class="truncate">Neues Label „{query.trim()}“</span>
            {/if}
        </button>
    {/each}
</TriggerWindow>
