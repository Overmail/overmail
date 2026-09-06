<script lang="ts">
    import {Input} from "$lib/components/ui/input";
    import {MagnifyingGlassIcon, XIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {
        value = $bindable(""),
        id,
        class: className = "",
    }: {
        /** What is typed. The screen filters on it; this field only holds it. */
        value?: string;
        id?: string;
        class?: string;
    } = $props();
</script>

<div class="relative {className}">
    <MagnifyingGlassIcon
            class="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2"
    />

    <!--
      `type="search"` for the semantics -- a screen reader announces a search field, and Escape
      clears it in the browsers that do that. Its own clear button is hidden: the one below is
      the one that is styled and labelled.
    -->
    <Input
            {id}
            type="search"
            class="pr-9 pl-9 [&::-webkit-search-cancel-button]:appearance-none"
            placeholder={$_("settings.knowledge.search.placeholder")}
            bind:value
    />

    {#if value.length > 0}
        <button
                type="button"
                class="text-muted-foreground hover:text-foreground absolute top-1/2 right-3 -translate-y-1/2 rounded-full"
                aria-label={$_("settings.knowledge.search.clear")}
                title={$_("settings.knowledge.search.clear")}
                onclick={() => (value = "")}
        >
            <XIcon class="size-4" />
        </button>
    {/if}
</div>
