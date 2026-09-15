<!--
    The header of one stretch of the listing: what it is, how much is in it, and the box that
    ticks all of it.

    One per level, so a listing cut by day and then by correspondent draws the day's header and
    the correspondent's under it. What a header stands for is a node of the layout, and ticking it
    is ticking every mail under it -- the deeper ones included, which is what makes the selection
    follow the shape of the list.
-->
<script lang="ts">
    import {untrack} from "svelte";
    import {locale, _} from "svelte-i18n";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {cn} from "$lib/utils";
    import {useRepositories} from "$lib/repository/repositories";
    import type {MailGroupNode} from "$lib/app/mails/mailLayout";
    import type {MailListViewModel} from "$lib/app/mails/MailListViewModel.svelte";
    import {getMailSelection} from "$lib/app/mails/mailSelection";
    import {CHECKBOX_REVEAL, SHOWN} from "./selectionReveal";

    let {
        node,
        list,
        pinned = false,
    }: {
        node: MailGroupNode;
        list: MailListViewModel;
        /**
         * Whether this is the copy that stays under the bar while its stretch is scrolled
         * through. The same header, without the air that sets a stretch off from the one above
         * it -- there is nothing above it up there.
         */
        pinned?: boolean;
    } = $props();

    const selection = getMailSelection();
    const {senders, inboxes} = useRepositories();

    /** One formatter per locale for the whole column, not one per header. */
    const formats = $derived.by(() => {
        const forLocale = $locale ?? undefined;
        return {
            month: new Intl.DateTimeFormat(forLocale, {month: "long"}),
            monthWithYear: new Intl.DateTimeFormat(forLocale, {month: "long", year: "numeric"}),
            day: new Intl.DateTimeFormat(forLocale, {day: "numeric", month: "long", year: "numeric"}),
        };
    });

    const label = $derived(node.label);

    /** A correspondent is a name this has to look up; the id alone says nothing to a reader. */
    const sender = $derived(label?.kind === "sender" ? senders.peek(label.id) : null);

    // In an effect, not while rendering: asking starts a load and writes state.
    $effect(() => {
        if (label?.kind === "sender") senders.request(label.id);
    });

    /** The accounts, read once for the headers that name one. */
    let accounts = $state<{id: string; username: string}[]>([]);

    $effect(() => {
        if (label?.kind !== "account") return;

        const controller = new AbortController();
        inboxes
            .list(controller.signal)
            .then((result) => (accounts = result.map((inbox) => ({id: inbox.id, username: inbox.username}))))
            .catch(() => {});

        return () => controller.abort();
    });

    const text = $derived.by(() => {
        if (label === null) return "";

        switch (label.kind) {
            case "today":
            case "yesterday":
            case "week":
            case "month":
                return $_(`mails.groups.${label.kind}`);

            case "calendarMonth": {
                // The year only when it is another one; within this year the month names itself.
                const date = new Date(label.year, label.month - 1, 1);
                if (Number.isNaN(date.getTime())) return node.path.at(-1) ?? "";

                const thisYear = label.year === new Date().getFullYear();
                return (thisYear ? formats.month : formats.monthWithYear).format(date);
            }

            case "year":
                return String(label.year);

            case "day": {
                // Whatever the key was, if it is no date this can read: a header that says the
                // key itself is worse than one that says a date, and better than a listing that
                // stops drawing because a label threw.
                const date = new Date(label.date);
                return Number.isNaN(date.getTime()) ? label.date : formats.day.format(date);
            }

            case "sender":
                return sender?.value?.name ?? sender?.value?.address ?? "…";

            case "account":
                return accounts.find((account) => account.id === label.id)?.username ?? "…";

            case "read":
                return $_(label.isRead ? "mails.groups.read" : "mails.groups.unread");

            case "archived":
                return $_(`filters.archive.${label.state === "Unarchive" ? "inbox" : label.state === "Archive" ? "archived" : "spam"}`);
        }
    });

    /**
     * The whole stretch, as the server named it when the box was last clicked. Null until then,
     * and what the box counts against in the meantime is what the listing holds of the stretch --
     * the rows the table has, which is what a reader can see the state of anyway.
     */
    let named = $state<string[] | null>(null);

    const ids = $derived(named ?? list.idsUnder(node));

    // A stretch that moved holds other mails: what the server named was about the listing as it
    // stood, so it is dropped and asked for again on the next click.
    $effect(() => {
        void node.path.join("/");
        void node.count;
        untrack(() => (named = null));
    });

    const picked = $derived(selection?.countOf(ids) ?? 0);
    const all = $derived(ids.length > 0 && picked === ids.length);

    /** Some of the stretch, not all of it -- the third state of the box. */
    const some = $derived(picked > 0 && !all);

    /**
     * Picks the stretch, or takes it back.
     *
     * In two steps, and both of them matter: the mails the table holds are ticked on the spot, so
     * the click answers at once, and the rest of the stretch follows when the server has named
     * it. A stretch is a day or a correspondent of mail, and only a fraction of it has ever been
     * paged in.
     *
     * Not guarded against a second click: the ids are asked for once and both clicks wait on the
     * same answer, so the last one is what the stretch ends up as.
     */
    async function pick(selected: boolean) {
        if (selection === null) return;

        selection.setAll(ids, selected);

        const stretch = await list.idsOfGroup(node);
        named = stretch;
        selection.setAll(stretch, selected);
    }
</script>

<!--
    The space above is what sets one stretch off from the one before it, and an outer level gets
    more of it than an inner one. The whole box is what `headerHeight` in MailTable says: the
    virtualizer sizes the row from that number, so a taller header here drifts the list unless
    that one follows.

    One small step in per level, and the mails under it take the same step (see `mailIndent` in
    MailTable): what says where a row belongs is the ladder the two make together, and a header
    that steps in any further only pushes its own text away from the list it is about.
-->
<div
        class={cn(
            "flex h-4 flex-row items-center gap-2.5 border-b pb-4 mb-1",
            pinned ? "mt-0" : node.level === 0 ? "mt-10" : "mt-4"
        )}
        style={`padding-left: ${0.75 + node.level * 0.75}rem`}
>
    {#if selection !== null}
        <!-- The square the mails below keep their avatars in, so the stretch's box stands at the
             head of their column. Held open whether the box is in it or not: a header whose text
             shifts sideways under the cursor is the list moving while it is being read. -->
        <div class="grid h-4 w-5 shrink-0 place-items-center">
            <!-- The third state is read off the selection like the other two, so what the
                 checkbox itself makes of a click on it is answered by the ticks that click
                 causes -- hence the setter that keeps its own counsel. -->
            <Checkbox
                    aria-label={$_("mails.selection.selectGroup", {values: {group: text}})}
                    bind:checked={() => all, (value) => void pick(value)}
                    bind:indeterminate={() => some, () => {}}
                    class={cn(CHECKBOX_REVEAL, picked > 0 && SHOWN)}
            />
        </div>
    {/if}

    <div class="flex flex-row items-center gap-1">
        <span class={cn("text-xs text-muted-foreground", node.level === 0 ? "font-medium" : "font-normal")}>
            {text}
        </span>
        <span class="text-muted-foreground/60 text-xs tabular-nums">{node.count}</span>
    </div>
</div>
