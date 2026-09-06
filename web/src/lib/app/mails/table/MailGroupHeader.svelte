<script lang="ts">
    import {untrack} from "svelte";
    import {locale, _} from "svelte-i18n";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {cn} from "$lib/utils";
    import type {MailGroupLabel} from "$lib/app/mails/grouping";
    import type {MailListViewModel} from "$lib/app/mails/MailListViewModel.svelte";
    import {getMailSelection} from "$lib/app/mails/mailSelection";
    import {CHECKBOX_REVEAL, SHOWN} from "./selectionReveal";

    let {
        label,
        count,
        /** Where the mails of this stretch start in the mailbox. */
        start,
        list,
    }: {
        label: MailGroupLabel;
        count: number;
        start: number;
        list: MailListViewModel;
    } = $props();

    const selection = getMailSelection();

    /** One formatter per locale for the whole column, not one per header. */
    const formats = $derived.by(() => {
        const forLocale = $locale ?? undefined;
        return {
            month: new Intl.DateTimeFormat(forLocale, {month: "long"}),
            monthWithYear: new Intl.DateTimeFormat(forLocale, {month: "long", year: "numeric"}),
        };
    });

    const text = $derived.by(() => {
        if (label.kind !== "calendarMonth") return $_(`mails.groups.${label.kind}`);

        // The year only when it is another one; within this year the month names itself.
        const date = new Date(label.year, label.month - 1, 1);
        const thisYear = label.year === new Date().getFullYear();
        return (thisYear ? formats.month : formats.monthWithYear).format(date);
    });

    /**
     * The whole stretch, as the server named it when the box was last clicked. Null until then,
     * and what the box counts against in the meantime is what the listing holds of the stretch --
     * the rows the table has, which is what a reader can see the state of anyway.
     */
    let named = $state<string[] | null>(null);

    const ids = $derived(named ?? list.idsIn(start, count));

    // A stretch that moved holds other mails: what the server named was about the mailbox as it
    // stood, so it is dropped and asked for again on the next click.
    $effect(() => {
        void start;
        void count;
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
     * it. A stretch is a day or a month of mail, and only a fraction of it has ever been paged in.
     *
     * Not guarded against a second click: the ids are asked for once and both clicks wait on the
     * same answer, so the last one is what the stretch ends up as.
     */
    async function pick(selected: boolean) {
        if (selection === null) return;

        selection.setAll(ids, selected);

        const stretch = await list.idsOfStretch(start, count);
        named = stretch;
        selection.setAll(stretch, selected);
    }
</script>

<!-- The space above is what sets one stretch off from the one before it. Its whole box is
     HEADER_HEIGHT in MailTable: the virtualizer sizes the row from that number, so a taller
     header here drifts the list unless that one follows. -->
<div class="flex flex-row items-center gap-2.5 mt-10 mb-1 h-4 pl-3 border-b pb-4">
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
        <span class="text-muted-foreground text-xs font-medium">{text}</span>
        <span class="text-muted-foreground/60 text-xs tabular-nums">{count}</span>
    </div>
</div>
