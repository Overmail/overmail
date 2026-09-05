<!--
    The labels of one mail, as a row of chips: what it is sorted under, why, and -- where the
    caller has somewhere to put the answer -- a way to sort it further.

    Not tied to a place: the panel beside the list, the mail's own page and the card on the stack
    all show the same row, and only the padding around it and what may be changed from there
    differ.
-->
<script lang="ts">
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";
    import {Badge} from "$lib/components/ui/badge";
    import {Input} from "$lib/components/ui/input";
    import * as Popover from "$lib/components/ui/popover";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {_} from "svelte-i18n";
    import LabelPicker from "$lib/app/labels/LabelPicker.svelte";
    import type {LabelSearchResult} from "$lib/app/labels/labelSearch";
    import {cn} from "$lib/utils";

    let {
        labels,
        class: className,
        onAddLabel,
        onCreateLabel,
        onRemoveLabel,
        onRestoreFocus,
    }: {
        labels: EmailLabel[],
        /** Where the row sits; it brings no padding of its own. */
        class?: string,
        /** What the plus leads to. Without it picking one only closes the list again. */
        onAddLabel?: (label: LabelSearchResult) => void,
        /** The same for the last row of the list: a label of that name does not exist yet. */
        onCreateLabel?: (name: string) => void,
        /** Without it a label carries no X, which is what a mail nobody may sort looks like. */
        onRemoveLabel?: (label: EmailLabel) => void,
        /**
         * Where the keyboard goes once the picker closes. Without it, back to the plus it was
         * opened from -- which is right where the row is part of a page, and wrong where the
         * keyboard belongs to something around it, as on the stack.
         */
        onRestoreFocus?: () => void,
    } = $props();

    /**
     * Whether anything may be added from here. Without either handler the plus leads nowhere, and
     * a row that only shows how a mail is sorted has no business carrying one.
     */
    const canAdd = $derived(onAddLabel !== undefined || onCreateLabel !== undefined);

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

<!-- A chip is the label's name over its own colour; `props` is what the tooltip below puts on its
     trigger, and nothing otherwise.

     `data-slot` is put back after them: the trigger brings its own, the badge passes whatever it
     is handed straight through to the element, and the tint of a coloured badge is written
     against `[data-slot=badge]` -- so without this the labels that carry a tooltip lose their
     colour and the ones that do not keep it. -->
{#snippet chip(label: EmailLabel, props: Record<string, unknown> = {})}
    <Badge
            {...props}
            data-slot="badge"
            variant="secondary"
            class="shrink-0 font-normal"
            color={label.color}
            onremove={onRemoveLabel && (() => onRemoveLabel(label))}
    >
        {label.name}
    </Badge>
{/snippet}

<div class={cn("flex flex-row flex-wrap items-center gap-1", className)}>
    {#each labels as label (label.id)}
        <!-- What the label means, and why this mail has it: the description is the label's own,
             the reason is the one the assistant gave for putting it here. A chip that carries
             neither says everything it has to say in its name and stays a plain one. -->
        {#if label.description || label.assignmentReason}
            <Tooltip.Root>
                <Tooltip.Trigger>
                    <!-- child, so the badge *is* the trigger: a trigger of its own would be a
                         button around a badge that carries a remove button itself. -->
                    {#snippet child({props})}
                        {@render chip(label, props)}
                    {/snippet}
                </Tooltip.Trigger>
                <Tooltip.Content side="bottom" class="flex-col items-start gap-0.5">
                    {#if label.description}
                        <p>{label.description}</p>
                    {/if}
                    {#if label.assignmentReason}
                        <p class="text-background/70">{label.assignmentReason}</p>
                    {/if}
                </Tooltip.Content>
            </Tooltip.Root>
        {:else}
            {@render chip(label)}
        {/if}
    {/each}

    {#if canAdd}
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
            <Popover.Content
                    side="bottom"
                    align="start"
                    class="w-64 gap-1 p-1.5"
                    onCloseAutoFocus={(event) => {
                        if (!onRestoreFocus) return;

                        event.preventDefault();
                        onRestoreFocus();
                    }}
            >
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
    {/if}
</div>
