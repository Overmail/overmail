<script lang="ts">
    import {fuzzySearch} from "$lib/app/my-stack/tags";
    import {cn} from "$lib/utils.js";

    let {
        tags = $bindable(),
        suggestions,
        onclose,
        class: className,
    }: {
        tags: string[];
        /** Every tag that already exists; the ones already on the mail are filtered out. */
        suggestions: readonly string[];
        /** Enter on an empty field: there is nothing left to add, so it means "done" here. */
        onclose?: () => void;
        class?: string;
    } = $props();

    let value = $state("");
    /** How many tags sit left of the text field. The field is what marks the caret position. */
    let cursor = $state(tags.length);
    /** Index into `matches`, or -1 while nothing is picked and Enter would create a new tag. */
    let highlighted = $state(-1);
    let field = $state<HTMLInputElement | null>(null);

    const matches = $derived(fuzzySearch(value, suggestions.filter((tag) => !tags.includes(tag))));

    function insert(tag: string) {
        const name = tag.trim();
        value = "";
        highlighted = -1;
        if (!name || tags.includes(name)) return;

        tags = [...tags.slice(0, cursor), name, ...tags.slice(cursor)];
        cursor += 1;
    }

    function removeAt(index: number) {
        if (index < 0 || index >= tags.length) return;

        tags = [...tags.slice(0, index), ...tags.slice(index + 1)];
        if (index < cursor) cursor -= 1;
    }

    function handleKey(event: KeyboardEvent) {
        // Only take over the arrow and delete keys once the caret has nothing left to walk through
        // in the text itself; otherwise editing a half-typed tag would jump out of it.
        const start = field?.selectionStart ?? 0;
        const end = field?.selectionEnd ?? 0;
        const atStart = start === 0 && end === 0;
        const atEnd = start === value.length && end === value.length;

        switch (event.key) {
            case "Enter":
                event.preventDefault();
                if (highlighted < 0 && !value.trim()) {
                    onclose?.();
                    return;
                }
                insert(highlighted >= 0 ? matches[highlighted] : value);
                return;
            case "ArrowDown":
                if (!matches.length) return;
                event.preventDefault();
                highlighted = highlighted + 1 >= matches.length ? -1 : highlighted + 1;
                return;
            case "ArrowUp":
                if (!matches.length) return;
                event.preventDefault();
                highlighted = highlighted < 0 ? matches.length - 1 : highlighted - 1;
                return;
            case "ArrowLeft":
                if (!atStart || cursor === 0) return;
                event.preventDefault();
                cursor -= 1;
                return;
            case "ArrowRight":
                if (!atEnd || cursor === tags.length) return;
                event.preventDefault();
                cursor += 1;
                return;
            case "Backspace":
                if (!atStart) return;
                event.preventDefault();
                removeAt(cursor - 1);
                return;
            case "Delete":
                if (!atEnd) return;
                event.preventDefault();
                removeAt(cursor);
        }
    }
</script>

<div class={cn("relative", className)}>
    {#if matches.length}
        <!-- Above the field: the whole bar sits at the bottom of the window. -->
        <ul
                class="absolute bottom-full left-0 mb-2 max-h-56 w-64 overflow-y-auto rounded-md border bg-background p-1 shadow-lg"
                role="listbox"
                aria-label="Bekannte Tags"
        >
            {#each matches as match, index (match)}
                <li
                        class={cn(
                            "cursor-default rounded-sm px-2 py-1 text-sm",
                            index === highlighted && "bg-accent text-accent-foreground",
                        )}
                        role="option"
                        aria-selected={index === highlighted}
                >{match}</li>
            {/each}
        </ul>
    {/if}

    <div class="flex flex-row flex-wrap items-center gap-1 rounded-lg border bg-background text-lg px-2 py-1.5">
        {#snippet input()}
            <!-- The field moves between the tags instead of a separate caret, so what you type
                 lands where the caret is. Moving it re-creates the element, hence the attachment
                 that hands the focus back. -->
            <input
                    bind:this={field}
                    bind:value
                    {@attach (node) => node.focus()}
                    onkeydown={handleKey}
                    class="min-w-32 flex-1 bg-transparent text-md outline-none"
                    aria-label="Tag hinzufügen"
                    placeholder={tags.length ? "" : "OTP, GitHub, Arbeit, …"}
            />
        {/snippet}

        {#each tags as tag, index (tag)}
            {#if index === cursor}{@render input()}{/if}
            <span class="rounded-sm bg-muted px-2 py-0.5 text-md text-muted-foreground">{tag}</span>
        {/each}
        {#if cursor >= tags.length}{@render input()}{/if}
    </div>
</div>
