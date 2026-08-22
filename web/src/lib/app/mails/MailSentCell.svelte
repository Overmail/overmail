<script lang="ts" module>
    const SHORT = new Intl.DateTimeFormat(undefined, {dateStyle: 'medium', timeStyle: 'short'});
    const FULL = new Intl.DateTimeFormat(undefined, {dateStyle: 'full', timeStyle: 'long'});
</script>

<script lang="ts">
    let {sentAt}: { sentAt: string } = $props();

    const sent = $derived(new Date(sentAt));
</script>

<time class="block truncate tabular-nums" datetime={sentAt} title={FULL.format(sent)}>
    {SHORT.format(sent)}
</time>
