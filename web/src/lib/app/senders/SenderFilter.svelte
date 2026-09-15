<!--
    A filter on correspondents: a chip that says who it is on, and a popover to pick them in.

    The label filter with people in it -- same chip, same search field holding the picked ones as
    removable chips, same list underneath that toggles. What differs is what a row shows (a face,
    a name and the address under it) and that this one is told what it is called: the same control
    is the "from" and the "to" of a listing, and only the caller knows which.
-->
<script lang="ts">
    import {CaretDownIcon, UserIcon} from "phosphor-svelte";
    import {tick, type Component} from "svelte";
    import {_} from "svelte-i18n";
    import {OvermailCircularAvatar} from "$lib/components/avatar";
    import {Badge} from "$lib/components/ui/badge";
    import * as Popover from "$lib/components/ui/popover";
    import {filterChip} from "$lib/app/filters/chip";
    import {summarisePicked} from "$lib/app/filters/summarise";
    import {displayName} from "$lib/app/mails/participants";
    import SenderPicker from "$lib/app/senders/SenderPicker.svelte";
    import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";
    import {cn} from "$lib/utils";

    let {
        senders = $bindable([]),
        title,
        icon = UserIcon,
        onSenderAdded,
        onSenderRemoved,
        class: className,
    }: {
        /**
         * Who the filter is on, in the order they were picked. Written here as they are toggled,
         * so a caller that only wants to read the selection can bind and stop there.
         */
        senders?: EmailParticipant[];
        /** What the chip calls itself -- "Von", "An". A colon is added while it is set. */
        title: string;
        icon?: Component;
        /** One was turned on. For a caller that saves a change rather than the whole list. */
        onSenderAdded?: (sender: EmailParticipant) => void;
        onSenderRemoved?: (sender: EmailParticipant) => void;
        /** Where the chip sits; it brings no margin of its own. */
        class?: string;
    } = $props();

    const Icon = $derived(icon);

    let open = $state(false);
    let query = $state("");

    let input: HTMLInputElement | null = $state(null);
    let picker: ReturnType<typeof SenderPicker> | undefined = $state();

    /** Nobody picked is not a filter, and the chip says so by looking like every other one. */
    const active = $derived(senders.length > 0);

    const summary = $derived(summarisePicked(senders.map(displayName)));

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
    function toggle(sender: EmailParticipant) {
        const picked = senders.find((entry) => entry.id === sender.id);
        if (picked) remove(picked);
        else add(sender);

        // The query has done its job: who it was typed for is a chip now. The field keeps the
        // caret -- a chip is a button and sits before it, so the popover's focus trap would
        // otherwise hand the focus to the X of the one that just appeared.
        query = "";
        void tick().then(() => input?.focus());
    }

    function add(sender: EmailParticipant) {
        // Only what a chip needs is kept: how much mail they have sent is a fact about them, not
        // about this filter.
        senders = [
            ...senders,
            {
                id: sender.id,
                name: sender.name,
                address: sender.address,
                avatarUrl: sender.avatarUrl,
                avatarPadding: sender.avatarPadding,
            },
        ];
        onSenderAdded?.(sender);
    }

    function remove(sender: EmailParticipant) {
        senders = senders.filter((entry) => entry.id !== sender.id);
        onSenderRemoved?.(sender);
    }

    function onKeydown(event: KeyboardEvent) {
        // Backspace in an empty field takes the last chip, the way every field that holds chips
        // does. Before the picker, which does not answer to Backspace anyway.
        if (event.key === "Backspace" && query === "" && senders.length > 0) {
            remove(senders[senders.length - 1]);
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
                <Icon class="h-lh" weight={active ? "fill" : "regular"}/>
                <span class={active ? "font-medium" : undefined}>
                    {active ? `${title}:` : title}
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

    <Popover.Content side="bottom" align="start" class="w-72 gap-1 p-1.5">
        <!-- A label, so clicking anywhere in the field lands in the input without a handler of
             its own -- including the gaps between the chips. Three rows tall from the start, so
             the popover does not grow under the cursor as people are picked. -->
        <label class="flex min-h-22 flex-row flex-wrap content-start items-center gap-1 px-2 py-1.5">
            {#each senders as sender (sender.id)}
                <Badge variant="secondary" class="font-normal" onremove={() => remove(sender)}>
                    <!-- The face rather than a colour: it is what tells two chips apart here. -->
                    <OvermailCircularAvatar
                            url={sender.avatarUrl}
                            padding={sender.avatarPadding}
                            name={displayName(sender)}
                            class="size-3.5"
                            fallbackClass="text-[0.5rem]"
                    />
                    {displayName(sender)}
                </Badge>
            {/each}

            <input
                    bind:this={input}
                    bind:value={query}
                    class="min-w-24 flex-1 bg-transparent text-sm outline-none"
                    placeholder={$_("senders.search")}
                    onkeydown={onKeydown}
            />
        </label>

        <div class="h-px w-full bg-border/50"></div>

        <SenderPicker
                bind:this={picker}
                {query}
                class="*:rounded-2xl"
                selected={senders.map((sender) => sender.id)}
                onSelect={toggle}
                onDismiss={() => (open = false)}
        />
    </Popover.Content>
</Popover.Root>
