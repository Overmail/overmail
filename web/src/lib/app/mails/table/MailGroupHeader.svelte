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

<span class="text-muted-foreground text-xs font-medium">{text}</span>
<span class="text-muted-foreground/60 text-xs tabular-nums">{count}</span>
