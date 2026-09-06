<script lang="ts">
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
     * The mails of this stretch the listing can name -- everything of it that has been paged in.
     *
     * Which is what the box here is about: a stretch nobody has scrolled through yet is longer
     * than this, and a mail whose page is not here has no id to tick. So the count beside the
     * label counts the stretch, and the box counts what the table holds of it.
     */
    const ids = $derived(list.idsIn(start, count));

    const picked = $derived(selection?.countOf(ids) ?? 0);
    const all = $derived(ids.length > 0 && picked === ids.length);

    /** Some of the stretch, not all of it -- the third state of the box. */
    const some = $derived(picked > 0 && !all);
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
            {#if ids.length > 0}
                <!-- The third state is read off the selection like the other two, so what the
                     checkbox itself makes of a click on it is answered by the tick that click
                     causes -- hence the setter that keeps its own counsel. -->
                <Checkbox
                        aria-label={$_("mails.selection.selectGroup", {values: {group: text}})}
                        bind:checked={() => all, (value) => selection.setAll(ids, value)}
                        bind:indeterminate={() => some, () => {}}
                        class={cn(CHECKBOX_REVEAL, picked > 0 && SHOWN)}
                />
            {/if}
        </div>
    {/if}

    <div class="flex flex-row items-center gap-1">
        <span class="text-muted-foreground text-xs font-medium">{text}</span>
        <span class="text-muted-foreground/60 text-xs tabular-nums">{count}</span>
    </div>
</div>
