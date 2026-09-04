<script lang="ts">
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";
    import {Badge} from "$lib/components/ui/badge";
    import {Input} from "$lib/components/ui/input";
    import * as Popover from "$lib/components/ui/popover";
    import {_} from "svelte-i18n";
    import LabelPicker from "$lib/app/labels/LabelPicker.svelte";
    import type {LabelSearchResult} from "$lib/app/labels/labelSearch";

    let {
        labels,
        onAddLabel,
        onCreateLabel,
        onRemoveLabel,
    }: {
        labels: EmailLabel[],
        /** What the plus leads to. Without it picking one only closes the list again. */
        onAddLabel?: (label: LabelSearchResult) => void,
        /** The same for the last row of the list: a label of that name does not exist yet. */
        onCreateLabel?: (name: string) => void,
        /** Without it a label carries no X, which is what a mail nobody may sort looks like. */
        onRemoveLabel?: (label: EmailLabel) => void,
    } = $props();

    let open = $state(false);
    let query = $state("");

    let input: HTMLInputElement | null = $state(null);
    let picker: ReturnType<typeof LabelPicker> | undefined = $state();

    // Opening starts over: the query from the last time says nothing about this one. The focus
    // goes to the field, because that is what the list is driven by -- arrows and Enter are
    // handed on from there.
    $effect(() => {
        if (!open) return;

        query = "";
        input?.focus();
    });
</script>

<div class="flex flex-row flex-wrap gap-1 px-6">
    {#each labels as label (label.id)}
        <Badge
                variant="secondary"
                class="shrink-0 font-normal"
                color={label.color}
                title={label.description ?? label.name}
                onremove={onRemoveLabel && (() => onRemoveLabel(label))}
        >
            {label.name}
        </Badge>
    {/each}

    <Popover.Root bind:open>
        <!--
            The trigger is bits-ui's own button with the badge inside it: a badge is a span, and a
            span with a click handler is not something a keyboard can reach.

            inline-flex, because a button otherwise lays its content out on a text baseline: the
            badge would sit in a line box of the button's line-height and end up a few pixels off
            the badges beside it.
        -->
        <Popover.Trigger class="inline-flex rounded-xs outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
            <Badge variant="outline" class="shrink-0 font-normal cursor-pointer">
                +
                <span class="sr-only">{$_('labels.add')}</span>
            </Badge>
        </Popover.Trigger>

        <!-- p-1.5 with rows at rounded-2xl inside a rounded-3xl box: the proportions the
             dropdown menu of this design system uses. -->
        <Popover.Content side="bottom" align="start" class="w-64 gap-1 p-1.5">
            <Input
                    bind:ref={input}
                    bind:value={query}
                    placeholder={$_('labels.search')}
                    class="h-8 border-0 bg-transparent shadow-none focus-visible:ring-0"
                    onkeydown={(event) => {
                        if (picker?.handleKey(event)) event.preventDefault();
                    }}
            />

            <!-- A label can be made from here: this is where somebody sorts one mail, and the
                 name they are looking for is often one that does not exist yet. -->
            <LabelPicker
                    bind:this={picker}
                    {query}
                    class="*:rounded-2xl"
                    exclude={labels.map((label) => label.id)}
                    allowCreationOfNewLabels
                    onSelect={(label) => {
                        onAddLabel?.(label);
                        open = false;
                    }}
                    onCreate={(name) => {
                        onCreateLabel?.(name);
                        open = false;
                    }}
                    onDismiss={() => (open = false)}
            />
        </Popover.Content>
    </Popover.Root>
</div>
