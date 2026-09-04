<script module lang="ts">
    /**
     * One formatter for the whole column. Building an Intl formatter costs far more than using
     * one, and `toLocaleString` builds a fresh one per call -- per cell, per render, down a list.
     */
    const format = new Intl.DateTimeFormat(undefined, {
        day: "numeric",
        month: "numeric",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
    });
</script>

<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";

    let {mail}: {mail: EmailMeta} = $props();

    /** Unix seconds off the wire. */
    const sentAt = $derived(new Date(mail.sent * 1000));
</script>

<time class="text-muted-foreground tabular-nums" datetime={sentAt.toISOString()}>
    {format.format(sentAt)}
</time>
