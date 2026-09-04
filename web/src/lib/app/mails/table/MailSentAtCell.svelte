<script lang="ts">
    import {locale} from "svelte-i18n";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {sentAtLabel} from "../sentAt";

    let {mail}: {mail: EmailMeta} = $props();

    /**
     * One set of formatters per locale for the whole column, not per cell: building an Intl
     * formatter costs far more than using one, and this is a list.
     */
    const formats = $derived.by(() => {
        const forLocale = $locale ?? undefined;
        return {
            time: new Intl.DateTimeFormat(forLocale, {hour: "2-digit", minute: "2-digit"}),
            date: new Intl.DateTimeFormat(forLocale, {day: "numeric", month: "short"}),
            dateWithYear: new Intl.DateTimeFormat(forLocale, {
                day: "numeric",
                month: "short",
                year: "2-digit",
            }),
            full: new Intl.DateTimeFormat(forLocale, {dateStyle: "long", timeStyle: "short"}),
        };
    });

    /** Unix seconds off the wire. */
    const sentAt = $derived(new Date(mail.sent * 1000));

    // Read as the cell is drawn rather than kept ticking: a row is re-drawn often enough, and a
    // list that is open at midnight showing "yesterday" for a moment is nobody's problem.
    const label = $derived(sentAtLabel(sentAt, new Date()));

    const shown = $derived.by(() => {
        switch (label.kind) {
            case "time":
                return formats.time.format(sentAt);
            case "date":
                return (label.withYear ? formats.dateWithYear : formats.date).format(sentAt);
        }
    });
</script>

<!-- The label is coarse on purpose, so the exact moment is one hover away. -->
<time
        class="text-muted-foreground tabular-nums"
        datetime={sentAt.toISOString()}
        title={formats.full.format(sentAt)}
>
    {shown}
</time>
