<!--
    A point in time as "5 minutes ago", kept current on its own. Nothing else in the tree changes
    when the label does, so the clock lives here rather than in every caller.
-->
<script lang="ts">
    import {onMount} from "svelte";
    import {formatDistance} from "date-fns";
    import {de, enUS} from "date-fns/locale";
    import {locale} from "svelte-i18n";

    let {
        date,
        title,
        class: className,
    }: {
        date: Date;
        /** Where the coarse label is not enough, e.g. the exact moment on hover. */
        title?: string;
        class?: string;
    } = $props();

    // date-fns has catalogs of its own, so the relative dates have to be pointed at the ui
    // language separately. `$locale` can be a regional tag such as `de-DE`.
    const dateLocale = $derived($locale?.slice(0, 2) === "de" ? de : enUS);

    // Half a minute: the shortest step date-fns makes is a minute, so this is what keeps the
    // label from being off by one for long.
    let now = $state(new Date());
    onMount(() => {
        const interval = setInterval(() => now = new Date(), 30_000);
        return () => clearInterval(interval);
    });
</script>

<time datetime={date.toISOString()} {title} class={className}>
    {formatDistance(date, now, {addSuffix: true, locale: dateLocale})}
</time>
