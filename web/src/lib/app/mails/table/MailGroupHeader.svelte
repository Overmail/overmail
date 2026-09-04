<script lang="ts">
    import {locale, _} from "svelte-i18n";
    import type {MailGroupLabel} from "$lib/app/mails/grouping";

    let {label, count}: {label: MailGroupLabel; count: number} = $props();

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
</script>

<!-- The space above is what sets one stretch off from the one before it. Its whole box is
     HEADER_HEIGHT in MailTable: the virtualizer sizes the row from that number, so a taller
     header here drifts the list unless that one follows. -->
<div class="flex flex-row items-center gap-1 mt-10 mb-1 h-4 ml-4">
    <span class="text-muted-foreground text-xs font-medium">{text}</span>
    <span class="text-muted-foreground/60 text-xs tabular-nums">{count}</span>
</div>
