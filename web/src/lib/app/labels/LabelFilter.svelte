<!--
    A filter on labels: a chip that says which ones it is on, and a popover to pick them in.

    The picking is the same list the assistant's chat and the mail panel use, only it toggles here
    instead of adding: this chooses what a set contains, and a label that is on has to be pickable
    to turn off. The ones that are on sit in the search field as removable chips, so the selection
    is where the typing happens rather than somewhere above it.

    Nothing about views: the chip is bound to a list of labels and says what changed. Where that
    list comes from and what it is for -- a view's filter, a search, a listing -- is the caller's.
-->
<script lang="ts">
    import {CaretDownIcon, TagIcon} from "phosphor-svelte";
    import {tick} from "svelte";
    import {_} from "svelte-i18n";
    import {Badge} from "$lib/components/ui/badge";
    import * as Popover from "$lib/components/ui/popover";
    import LabelPicker from "$lib/app/labels/LabelPicker.svelte";
    import type {PickedLabel} from "$lib/app/labels/labelSearch";
    import {filterChip} from "$lib/app/filters/chip";
    import {useRepositories} from "$lib/repository/repositories";
    import {summarisePicked} from "$lib/app/filters/summarise";
    import {cn} from "$lib/utils";

    let {
        ids = $bindable([]),
        onLabelAdded,
        onLabelRemoved,
        class: className,
    }: {
        /**
         * The labels the filter is on, by id -- which is what a view holds, and the only thing
         * that still means the same label after it was renamed. What they are called is looked up
         * here, so a caller can hand over what it read out of a stored view and nothing else.
         */
        ids?: string[];
        /** One label was turned on. For a caller that saves a change rather than the whole list. */
        onLabelAdded?: (label: PickedLabel) => void;
        onLabelRemoved?: (label: PickedLabel) => void;
        /** Where the chip sits; it brings no margin of its own. */
        class?: string;
    } = $props();

    const {labels: known} = useRepositories();

    // In an effect, not while rendering: asking starts a load and writes state.
    $effect(() => {
        for (const id of ids) known.request(id);
    });

    /**
     * The picked labels as far as they can be named right now.
     *
     * A label whose name has not arrived yet is still a chip -- it is in the filter either way,
     * and a row that appears late would move everything beside it.
     */
    const labels = $derived(
        ids.map((id) => {
            const held = known.peek(id).value;

            return {id, name: held?.name ?? "…", color: held?.color ?? "var(--muted)"};
        })
    );

    let open = $state(false);
    let query = $state("");

    let input: HTMLInputElement | null = $state(null);
    let picker: ReturnType<typeof LabelPicker> | undefined = $state();

    /** Nothing picked is not a filter, and the chip says so by looking like every other one. */
    const active = $derived(ids.length > 0);

    const summary = $derived(summarisePicked(labels.map((label) => label.name)));

    // The same pill the toggle filters are, so a row of them reads as one kind of control; this
    // one is a single button, so both slots go on it.
    const chip = $derived(filterChip({active}));

    // Opening starts over: the query from the last time says nothing about this one. The focus
    // goes to the field, because that is what the list is driven by -- arrows and Enter are
    // handed on from there.
    $effect(() => {
        if (!open) return;

        query = "";
        input?.focus();
    });

    /** What picking a row does, and the only way into the list that is a search result. */
    function toggle(label: PickedLabel) {
        const picked = labels.find((entry) => entry.id === label.id);
        if (picked) remove(picked);
        else add(label);

        // The query has done its job: what it was typed for is a chip now, and leaving it in the
        // field means typing the next name into the middle of the last one. The chips are what
        // says what was picked; the field is for looking.
        query = "";

        // And the field keeps the caret. A chip is a button and sits before the field, so the
        // popover's focus trap hands the focus to the X of the one that just appeared -- after
        // the chip is on screen, or there is nothing to take it back from.
        void tick().then(() => input?.focus());
    }

    function add(label: PickedLabel) {
        // The id alone: what the label is called is the label's business, and a filter that kept
        // a copy of the name would show the old one after a rename.
        ids = [...ids, label.id];
        onLabelAdded?.(label);
    }

    function remove(label: PickedLabel) {
        ids = ids.filter((id) => id !== label.id);
        onLabelRemoved?.(label);
    }

    function onKeydown(event: KeyboardEvent) {
        // Backspace in an empty field takes the last chip, the way every field that holds chips
        // does. Before the picker, which does not answer to Backspace anyway.
        if (event.key === "Backspace" && query === "" && labels.length > 0) {
            remove(labels[labels.length - 1]);
            return;
        }

        if (picker?.handleKey(event)) event.preventDefault();
    }
</script>

<Popover.Root bind:open>
    <Popover.Trigger>
        <!-- child, so the trigger *is* the button: a chip is a div, and a div with a click
             handler is not something a keyboard can reach. -->
        {#snippet child({props})}
            <button
                    {...props}
                    type="button"
                    class={cn(chip.root(), chip.action(), className)}
            >
                <TagIcon class="h-lh" weight={active ? "fill" : "regular"}/>
                <span class={active ? "font-medium" : undefined}>
                    {active ? $_("labels.filter.titleActive") : $_("labels.filter.title")}
                </span>
                {#if active}
                    <span>
                        {summary.shown.join(", ")}{summary.rest === 0
                            ? ""
                            : ` ${$_("filters.more", {values: {count: summary.rest}})}`}
                    </span>
                {/if}
                <CaretDownIcon class="h-lh"/>
            </button>
        {/snippet}
    </Popover.Trigger>

    <!-- p-1.5 with rows at rounded-2xl inside a rounded-3xl box: the proportions the dropdown
         menu of this design system uses, and what the mail panel's picker sits in. -->
    <Popover.Content side="bottom" align="start" class="w-72 gap-1 p-1.5">
        <!-- A label, so clicking anywhere in the field lands in the input without a handler of
             its own -- including the gaps between the chips.

             Three rows of chips tall from the start: the field is what grows as labels are
             picked, and a popover that gets taller under the cursor moves the list somebody is
             picking from. `content-start` keeps the rows at the top of that space, `items-center`
             still centres the field against the chips beside it. -->
        <label class="flex min-h-22 flex-row flex-wrap content-start items-center gap-1 px-2 py-1.5">
            {#each labels as label (label.id)}
                <Badge
                        variant="secondary"
                        class="font-normal"
                        color={label.color}
                        onremove={() => remove(label)}
                >
                    {label.name}
                </Badge>
            {/each}

            <input
                    bind:this={input}
                    bind:value={query}
                    class="min-w-24 flex-1 bg-transparent text-sm outline-none"
                    placeholder={$_("labels.search")}
                    onkeydown={onKeydown}
            />
        </label>

        <div class="h-px w-full bg-border/50"></div>

        <!-- No creating from here: a filter picks among the labels there are, and one that was
             made on the spot carries no mail to find. -->
        <LabelPicker
                bind:this={picker}
                {query}
                class="*:rounded-2xl"
                selected={ids}
                onSelect={toggle}
                onDismiss={() => (open = false)}
        />
    </Popover.Content>
</Popover.Root>
