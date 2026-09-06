<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {XIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {
        keywords,
        draft = $bindable(""),
        id,
        full = false,
        disabled = false,
        placeholder = "",
        oncommit,
        onremove,
        oneditlast,
    }: {
        /** The chips, as the form holds them -- already normalized. */
        keywords: string[],
        /** What is typed and not a chip yet. Bound, because what did not fit stays in it. */
        draft: string,
        id: string,
        /** Whether the list is at its limit; the field then only says so. */
        full?: boolean,
        disabled?: boolean,
        placeholder?: string,
        /** Turn what is in the field into chips. */
        oncommit: () => void,
        onremove: (keyword: string) => void,
        /** Backspace in an empty field: take the last chip back for correcting. */
        oneditlast: () => void,
    } = $props();
</script>

<!--
  One field to the eye, made of the chips and an input: clicking anywhere in it starts typing, so
  the row of chips does not become a place where clicks go nowhere.
-->
<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
<div
        class="bg-input/50 focus-within:border-ring focus-within:ring-ring/30 flex min-h-9 w-full flex-row
               flex-wrap items-center gap-1 rounded-3xl border border-transparent px-2 py-1
               transition-[color,box-shadow,background-color] focus-within:ring-3
               has-disabled:opacity-50"
        onclick={(event) => {
            if (event.target === event.currentTarget) document.getElementById(id)?.focus();
        }}
        role="presentation"
>
    {#each keywords as keyword (keyword)}
        <Badge variant="secondary" class="gap-1 pr-1">
            {keyword}
            <button
                    type="button"
                    {disabled}
                    class="hover:text-foreground text-muted-foreground rounded-full disabled:pointer-events-none"
                    aria-label={$_("settings.knowledge.form.removeKeyword", {values: {keyword}})}
                    title={$_("settings.knowledge.form.removeKeyword", {values: {keyword}})}
                    onclick={() => onremove(keyword)}
            >
                <XIcon class="size-3" />
            </button>
        </Badge>
    {/each}

    <input
            {id}
            {disabled}
            type="text"
            class="placeholder:text-muted-foreground min-w-32 flex-1 bg-transparent px-1 text-base outline-none
                   disabled:cursor-not-allowed md:text-sm"
            placeholder={keywords.length === 0 ? placeholder : ""}
            bind:value={draft}
            oninput={() => {
                // A comma ends a keyword, whether it was typed or pasted with a whole list.
                if (draft.includes(",")) oncommit();
            }}
            onkeydown={(event) => {
                if (event.key === "Enter") {
                    // The dialog's form would submit on it, and here Enter means "that is a
                    // keyword" -- the footer button is how the entry is written.
                    event.preventDefault();
                    oncommit();
                } else if (event.key === "Backspace" && draft.length === 0) {
                    event.preventDefault();
                    oneditlast();
                }
            }}
            onblur={() => oncommit()}
    />
</div>

{#if full}
    <p class="text-muted-foreground text-xs">{$_("settings.knowledge.form.keywordsFull")}</p>
{/if}
