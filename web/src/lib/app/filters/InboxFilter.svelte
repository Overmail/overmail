<!--
    A filter on the mail accounts connected to this Overmail account: a chip that says which ones
    it is on, and a popover to tick them in.

    The label and sender filters with a list that is already known -- somebody has a handful of
    mailboxes, not a directory of them, so there is nothing to search and the rows are simply all
    of them. Everything else is the same: the chip, the ticking, and a selection that is empty
    when the filter is not set.

    The ids are the accounts' own, which is what `ViewFilter.imapAccountIds` holds.
-->
<script lang="ts" module>
    /** The least an account has to be to be named on the chip; an [Inbox] is one. */
    export type PickedInbox = {
        id: string;
        /** The imap login, which for most providers is the address itself. */
        username: string;
    };
</script>

<script lang="ts">
    import {CaretDownIcon, CheckIcon, EnvelopeSimpleIcon, WarningCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import * as Popover from "$lib/components/ui/popover";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {filterChip} from "$lib/app/filters/chip";
    import {summarisePicked} from "$lib/app/filters/summarise";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import {cn} from "$lib/utils";

    let {
        accounts = $bindable([]),
        onAccountAdded,
        onAccountRemoved,
        class: className,
    }: {
        /**
         * The accounts the filter is on, in the order they were picked. Written here as they are
         * toggled, so a caller that only wants to read the selection can bind and stop there.
         */
        accounts?: PickedInbox[];
        /** One was turned on. For a caller that saves a change rather than the whole list. */
        onAccountAdded?: (account: PickedInbox) => void;
        onAccountRemoved?: (account: PickedInbox) => void;
        /** Where the chip sits; it brings no margin of its own. */
        class?: string;
    } = $props();

    const {inboxes} = useRepositories();

    let open = $state(false);

    let available: Inbox[] = $state([]);
    let listState: "loading" | "ready" | "failed" = $state("loading");

    /** No account picked is not a filter, and the chip says so by looking like every other one. */
    const active = $derived(accounts.length > 0);

    const summary = $derived(summarisePicked(accounts.map((account) => account.username)));

    const chip = $derived(filterChip({active}));

    // Read when the popover opens rather than once on mount: the list is one short request, it is
    // nothing this holds on to, and an account connected in another tab should be in it. What was
    // read last stays on screen while the new answer is on its way, so the list does not blink.
    $effect(() => {
        if (!open) return;

        const controller = new AbortController();
        if (available.length === 0) listState = "loading";

        inboxes
            .list(controller.signal)
            .then((result) => {
                available = result;
                listState = "ready";
            })
            .catch(() => {
                if (controller.signal.aborted) return;
                listState = "failed";
            });

        return () => controller.abort();
    });

    function toggle(account: PickedInbox) {
        const picked = accounts.find((entry) => entry.id === account.id);
        if (picked) remove(picked);
        else add(account);
    }

    function add(account: PickedInbox) {
        // Only what a chip needs is kept: how much mail came through an account, and whether its
        // importer is paused, are facts about the account and not about this filter.
        accounts = [...accounts, {id: account.id, username: account.username}];
        onAccountAdded?.(account);
    }

    function remove(account: PickedInbox) {
        accounts = accounts.filter((entry) => entry.id !== account.id);
        onAccountRemoved?.(account);
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
                <EnvelopeSimpleIcon class="h-lh" weight={active ? "fill" : "regular"}/>
                <span class={active ? "font-medium" : undefined}>
                    {active ? `${$_("filters.account.title")}:` : $_("filters.account.title")}
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
        <div class="max-h-64 overflow-y-auto *:rounded-2xl">
            {#if listState === "loading" && available.length === 0}
                <!-- Two rows, not a spinner: the list is about to be a list, and one that fills
                     in reads better than one that first says there is nothing. -->
                {#each [0, 1] as row (row)}
                    <div class="flex items-center gap-2 px-2 py-1.5">
                        <Skeleton class="size-5 shrink-0 rounded-full"/>
                        <Skeleton class="h-3.5 w-40"/>
                    </div>
                {/each}
            {:else if listState === "failed" && available.length === 0}
                <div class="flex items-start gap-2 px-2 py-1.5 text-destructive">
                    <WarningCircleIcon class="mt-0.5 size-4 shrink-0"/>
                    <span>{$_("filters.account.failed")}</span>
                </div>
            {:else if available.length === 0}
                <div class="px-2 py-1.5 text-muted-foreground">{$_("filters.account.empty")}</div>
            {:else}
                {#each available as account (account.id)}
                    <button
                            type="button"
                            class="flex w-full items-center gap-2 px-2 py-1.5 text-left hover:bg-accent hover:text-accent-foreground"
                            onclick={() => toggle(account)}
                    >
                        <EnvelopeSimpleIcon class="size-3.5 shrink-0"/>
                        <!-- The host under the login: two accounts at the same provider are told
                             apart by the login, two logins of the same name by the host. -->
                        <span class="flex min-w-0 flex-1 flex-col">
                            <span class="truncate">{account.username}</span>
                            <span class="truncate text-xs text-muted-foreground">{account.host}</span>
                        </span>
                        {#if accounts.some((entry) => entry.id === account.id)}
                            <CheckIcon class="size-3.5 shrink-0"/>
                        {/if}
                        <span class="ms-auto text-xs text-muted-foreground">
                            {$_("senders.emailCount", {values: {count: account.emailCount}})}
                        </span>
                    </button>
                {/each}
            {/if}
        </div>
    </Popover.Content>
</Popover.Root>
