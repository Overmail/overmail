<!--
    What the mailbox's own bar becomes while mails are picked: how many they are, whose they are,
    and the two things that can be done to all of them at once.

    In the place of the title rather than beside it, and only for as long as something is ticked --
    which is also what scopes the shortcuts, because they are registered from here.

    The mails it can say anything about are the ones the repository holds: the rows near the
    viewport, the ones recently there, and the five in the stack, which this subscribes to itself.
    A stretch picked through its header is longer than that, so the buttons read the state off what
    is known and act on every id -- see [known].
-->
<script lang="ts">
    import {_} from "svelte-i18n";
    import {createHotkey} from "@tanstack/svelte-hotkeys";
    import {ArchiveIcon, EnvelopeSimpleIcon, EnvelopeSimpleOpenIcon, TrayArrowDownIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {Spinner} from "$lib/components/ui/spinner";
    import * as Kbd from "$lib/components/ui/kbd";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import {OvermailCircularAvatar} from "$lib/components/avatar";
    import {useRepositories} from "$lib/repository/repositories";
    import {displayName} from "$lib/app/mails/participants";
    import type {MailSelection} from "$lib/app/mails/mailSelection";

    let {selection}: {selection: MailSelection} = $props();

    /** How many faces the stack shows before the rest of the picked mails become a number. */
    const STACK = 5;

    const {mails} = useRepositories();

    /** In the order they were ticked in, so the stack does not reshuffle as more are picked. */
    const ids = $derived(selection.ids);

    const stackIds = $derived(ids.slice(0, STACK));

    // The five in the stack are kept current whatever the table is showing: a mail picked and
    // then scrolled past would otherwise lose its face along with its row.
    $effect(() => {
        const releases = stackIds.map((id) => mails.subscribe(id));
        return () => releases.forEach((release) => release());
    });

    const stack = $derived(stackIds.map((id) => ({id, mail: mails.peek(id).value})));

    /** The picked mails beyond the stack, which is what the `+n` says. */
    const more = $derived(selection.count - stackIds.length);

    /**
     * The picked mails this holds -- everything the two buttons can read a state off.
     *
     * Not all of them, and it does not have to be: what the buttons need to know is whether there
     * is anything left to do, and a mail nobody has looked at cannot change that answer for the
     * better. What they *act* on is every id -- the bulk routes take the lot.
     */
    const known = $derived(ids.map((id) => mails.peek(id).value).filter((mail) => mail !== null));

    /**
     * Read unless every picked mail that is known is read already -- and then the button takes it
     * back instead. The same rule the panel's button follows, over a selection rather than a mail.
     */
    const markAsRead = $derived(known.length === 0 || known.some((mail) => !mail.isRead));

    /** Away unless they are all away already, in which case it is the way back into the mailbox. */
    const archiveThem = $derived(
        known.length === 0 || known.some((mail) => mail.archiveState === "unarchive")
    );

    /** Which button is working, so it says so and neither of them is pressed twice. */
    let running = $state<"read" | "archive" | null>(null);

    /**
     * Reads what the button says now, then does that to all of them.
     *
     * Read out before the first write on purpose: the state the button shows follows the mails,
     * and the first answer coming back would otherwise flip it half way through the selection.
     */
    async function setReadState() {
        if (running !== null) return;

        // The ids as they are now: what is ticked can change while this runs, and a write that
        // followed the selection would be a loop over a moving target.
        const picked = ids;
        const isRead = markAsRead;

        running = "read";
        try {
            await mails.setReadAll(picked, isRead);
        } finally {
            running = null;
        }
    }

    /**
     * The same for where the mails stand -- and this one ends the selection: what was archived is
     * out of the mailbox, so keeping it ticked would be a bar counting rows nobody can see.
     */
    async function setArchiveState() {
        if (running !== null) return;

        const picked = ids;
        const state = archiveThem ? "archive" : "unarchive";

        running = "archive";
        try {
            await mails.setArchiveStateAll(picked, state);
            selection.clear();
        } finally {
            running = null;
        }
    }

    // Shift and a letter, so they are the bulk of what the panel's own `A` does to one mail -- and
    // they are distinct keys to the manager, so a mail open beside the list still answers to `A`.
    // Registered here, which means they are only live while something is picked.
    createHotkey("Shift+R", () => void setReadState());
    createHotkey("Shift+A", () => void setArchiveState());
</script>

<div class="flex w-full items-center gap-3">
    <span class="text-sm font-medium">
        {$_("mails.selection.count", {values: {count: selection.count}})}
    </span>

    <!-- Overlapping, in the order they were picked. The ring is the bar's own background, which
         is what cuts one face out of the one behind it. -->
    <div class="flex -space-x-1.5">
        {#each stack as {id, mail} (id)}
            {#if mail === null}
                <Skeleton class="ring-background size-6 rounded-full ring-2"/>
            {:else}
                <!-- The name on a wrapper rather than on the avatar: whose face this is, for a
                     stack where the only other thing a reader has is the count. -->
                <span class="inline-flex" title={displayName(mail.sender)}>
                    <OvermailCircularAvatar
                            url={mail.sender.avatarUrl}
                            padding={mail.sender.avatarPadding}
                            name={displayName(mail.sender)}
                            class="ring-background size-6 ring-2"
                            fallbackClass="text-[10px]"
                    />
                </span>
            {/if}
        {/each}
    </div>

    {#if more > 0}
        <span class="text-muted-foreground -ms-1 text-xs tabular-nums">
            {$_("mails.selection.more", {values: {count: more}})}
        </span>
    {/if}

    <div class="flex items-center gap-1">
        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon-sm"
                        disabled={running !== null}
                        onclick={() => void setReadState()}
                >
                    {#if running === "read"}
                        <Spinner/>
                    {:else if markAsRead}
                        <EnvelopeSimpleOpenIcon/>
                    {:else}
                        <EnvelopeSimpleIcon/>
                    {/if}
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                {$_(markAsRead ? "mails.selection.markRead" : "mails.selection.markUnread")}
                <Kbd.Group>
                    <Kbd.Root>⇧</Kbd.Root>
                    <Kbd.Root>R</Kbd.Root>
                </Kbd.Group>
            </Tooltip.Content>
        </Tooltip.Root>

        <Tooltip.Root>
            <Tooltip.Trigger>
                <Button
                        variant="ghost"
                        size="icon-sm"
                        disabled={running !== null}
                        onclick={() => void setArchiveState()}
                >
                    {#if running === "archive"}
                        <Spinner/>
                    {:else if archiveThem}
                        <ArchiveIcon/>
                    {:else}
                        <TrayArrowDownIcon/>
                    {/if}
                </Button>
            </Tooltip.Trigger>

            <Tooltip.Content>
                {$_(archiveThem ? "mails.selection.archive" : "mails.selection.unarchive")}
                <Kbd.Group>
                    <Kbd.Root>⇧</Kbd.Root>
                    <Kbd.Root>A</Kbd.Root>
                </Kbd.Group>
            </Tooltip.Content>
        </Tooltip.Root>
    </div>
</div>
