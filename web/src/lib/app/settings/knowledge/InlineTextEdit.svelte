<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {Textarea} from "$lib/components/ui/textarea";
    import {CheckIcon, XIcon} from "phosphor-svelte";
    import type {InlineFailure} from "$lib/app/settings/knowledge/inlineEditing.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        value,
        multiline = false,
        label,
        saving = false,
        failure = null,
        onsave,
        oncancel,
        ontype,
    }: {
        /** What the cell reads now; the draft starts here and Escape goes back to it. */
        value: string,
        /** A paragraph rather than a line: opens as a textarea with buttons under it. */
        multiline?: boolean,
        /** What the field is, for anybody who cannot see which cell it sits in. */
        label: string,
        saving?: boolean,
        /** Why the last attempt was refused, shown under the field. */
        failure?: InlineFailure | null,
        onsave: (value: string) => void,
        oncancel: () => void,
        /** The first keystroke after a refusal, so the message can go with it. */
        ontype?: () => void,
    } = $props();

    /**
     * What is being typed. Held here and not bound outwards: the cell still shows the entry the
     * server has until a save comes back, so a failed one leaves nothing half-written behind.
     */
    // svelte-ignore state_referenced_locally
    let draft = $state(value);

    let field: HTMLInputElement | HTMLTextAreaElement | null = $state(null);

    // The double-click that opened this is not a click into the field, so without this nothing
    // would be focused. A line is usually replaced whole, a paragraph usually added to.
    $effect(() => {
        const node = field;
        if (!node) return;

        node.focus();
        if (node instanceof HTMLInputElement) node.select();
        else node.setSelectionRange(node.value.length, node.value.length);
    });

    // Opens at the height of the text it holds rather than at some default number of rows: the
    // point of editing in the row is seeing the whole entry while correcting it.
    $effect(() => {
        const node = field;
        if (!node || !multiline) return;

        // Read while typing too, so the field grows with the paragraph instead of scrolling.
        draft;
        node.style.height = "auto";
        node.style.height = `${node.scrollHeight + node.offsetHeight - node.clientHeight}px`;
    });

    /**
     * Escape always goes back. Enter saves a line; in a paragraph Enter is a line break, so there
     * it takes the modifier -- Cmd on a Mac, Ctrl everywhere else, which is why both are read.
     */
    function onkeydown(event: KeyboardEvent) {
        if (event.key === "Escape") {
            event.preventDefault();
            oncancel();
        } else if (event.key === "Enter" && (!multiline || event.metaKey || event.ctrlKey)) {
            event.preventDefault();
            onsave(draft);
        }
    }

    /**
     * Leaving the field ends the edit only when nothing was typed.
     *
     * Clicking somewhere else is not "discard what I wrote", and it is not "save it" either --
     * the row cannot know which. Closing an untouched field keeps rows from being left open all
     * over the table, and anything else waits for Enter, Escape or the buttons.
     */
    function onblur() {
        if (draft === value) oncancel();
    }
</script>

<div class="flex w-full flex-col gap-1">
    {#if multiline}
        <Textarea
                bind:ref={field}
                bind:value={draft}
                aria-label={label}
                aria-invalid={failure !== null}
                class="min-h-0 px-2 py-1.5 text-sm"
                {onkeydown}
                {onblur}
                oninput={() => ontype?.()}
        />

        <!-- Cmd+Enter is nothing anybody guesses, so the two ways out are also two buttons. -->
        <div class="flex flex-row items-center gap-1">
            <Button
                    variant="ghost"
                    size="icon-sm"
                    disabled={saving}
                    aria-label={$_("settings.knowledge.inline.apply")}
                    title={$_("settings.knowledge.inline.apply")}
                    onclick={() => onsave(draft)}
            >
                <CheckIcon />
            </Button>
            <Button
                    variant="ghost"
                    size="icon-sm"
                    disabled={saving}
                    aria-label={$_("settings.knowledge.inline.discard")}
                    title={$_("settings.knowledge.inline.discard")}
                    onclick={() => oncancel()}
            >
                <XIcon />
            </Button>
        </div>
    {:else}
        <Input
                bind:ref={field}
                bind:value={draft}
                type="text"
                aria-label={label}
                aria-invalid={failure !== null}
                class="h-8 px-2 text-sm"
                {onkeydown}
                {onblur}
                oninput={() => ontype?.()}
        />
    {/if}

    <div aria-live="polite" class="text-xs">
        {#if saving}
            <span class="text-muted-foreground">{$_("settings.knowledge.inline.saving")}</span>
        {:else if failure === "nameTaken"}
            <!-- The one refusal that is not a retry: another name is what makes it work. -->
            <span class="text-destructive">{$_("settings.knowledge.form.nameTaken")}</span>
        {:else if failure}
            <span class="text-destructive">{$_("settings.knowledge.inline.failed")}</span>
        {/if}
    </div>
</div>
