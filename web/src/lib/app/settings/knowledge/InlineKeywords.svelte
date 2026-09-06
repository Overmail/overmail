<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {PlusIcon, XIcon} from "phosphor-svelte";
    import {MAX_KEYWORDS, type InlineFailure} from "$lib/app/settings/knowledge/inlineEditing.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        keywords,
        saving = false,
        failure = null,
        onadd,
        onremove,
    }: {
        /** The chips as the server has them; a save is what changes this, never the field below. */
        keywords: string[],
        saving?: boolean,
        failure?: InlineFailure | null,
        /**
         * Writes the keyword. Answers false when the entry stayed as it was, which is what keeps
         * the typed word in the field instead of dropping it.
         */
        onadd: (raw: string) => Promise<boolean>,
        onremove: (keyword: string) => void,
    } = $props();

    /** Whether the field for a new keyword is open. Closed is the row's read-only look. */
    let adding = $state(false);
    let draft = $state("");
    let field: HTMLInputElement | null = $state(null);

    const full = $derived(keywords.length >= MAX_KEYWORDS);

    // The plus is a click, not a click into a field, so the field it opens has to take the focus.
    $effect(() => {
        field?.focus();
    });

    /**
     * The field stays open after a keyword went in: entries get their words in bursts, and
     * reaching for the plus between each one is the slow way to do that.
     */
    async function add() {
        if (draft.trim().length === 0) {
            adding = false;
            return;
        }
        if (await onadd(draft)) draft = "";
    }

    function close() {
        adding = false;
        draft = "";
    }
</script>

<div class="flex max-w-xs flex-col gap-1">
    <div class="flex flex-row flex-wrap items-center gap-1">
        {#if keywords.length === 0 && !adding}
            <span class="text-muted-foreground">{$_("settings.knowledge.list.noKeywords")}</span>
        {/if}

        {#each keywords as keyword (keyword)}
            <Badge variant="secondary" class="gap-1 pr-1">
                {keyword}
                <!--
                  Rendered all the time and only faded in with the row, like the edit and delete
                  buttons: a chip that changes width on hover would make the whole cell jump.
                -->
                <button
                        type="button"
                        class="text-muted-foreground hover:text-foreground rounded-full opacity-0 transition-opacity
                               group-hover/row:opacity-100 focus-visible:opacity-100"
                        aria-label={$_("settings.knowledge.form.removeKeyword", {values: {keyword}})}
                        title={$_("settings.knowledge.form.removeKeyword", {values: {keyword}})}
                        onclick={() => onremove(keyword)}
                >
                    <XIcon class="size-3" />
                </button>
            </Badge>
        {/each}

        {#if adding}
            <!--
              Deliberately without search or suggestions: the words are the user's own, and a
              keyword that has to be found in a list first is slower to add than to type.
            -->
            <input
                    bind:this={field}
                    bind:value={draft}
                    type="text"
                    class="bg-input/50 focus-visible:border-ring focus-visible:ring-ring/30 h-6 w-32 rounded-full
                           border border-transparent px-2 text-xs outline-none transition-[color,box-shadow]
                           focus-visible:ring-3"
                    aria-label={$_("settings.knowledge.inline.newKeyword")}
                    placeholder={$_("settings.knowledge.inline.newKeyword")}
                    onkeydown={(event) => {
                        if (event.key === "Enter") {
                            event.preventDefault();
                            void add();
                        } else if (event.key === "Escape") {
                            event.preventDefault();
                            close();
                        }
                    }}
                    onblur={() => {
                        // Only an untouched field closes itself; a word that was typed and not
                        // confirmed yet is not something a click somewhere else should throw away.
                        if (draft.trim().length === 0) close();
                    }}
            />
        {:else}
            <button
                    type="button"
                    disabled={full}
                    class="text-muted-foreground hover:text-foreground hover:bg-accent flex size-5 shrink-0
                           items-center justify-center rounded-full opacity-0 transition
                           group-hover/row:opacity-100 focus-visible:opacity-100 disabled:pointer-events-none"
                    aria-label={$_("settings.knowledge.inline.addKeyword")}
                    title={full
                        ? $_("settings.knowledge.form.keywordsFull")
                        : $_("settings.knowledge.inline.addKeyword")}
                    onclick={() => (adding = true)}
            >
                <PlusIcon class="size-3" />
            </button>
        {/if}
    </div>

    <div aria-live="polite" class="text-xs">
        {#if saving}
            <span class="text-muted-foreground">{$_("settings.knowledge.inline.saving")}</span>
        {:else if failure}
            <span class="text-destructive">{$_("settings.knowledge.inline.failed")}</span>
        {/if}
    </div>
</div>
