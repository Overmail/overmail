<script module lang="ts">
    /**
     * One formatter for the whole column. Building an Intl formatter costs far more than using
     * one, and `toLocaleDateString` builds a fresh one per call -- per cell, per render, down a
     * list.
     */
    const format = new Intl.DateTimeFormat(undefined, {
        day: 'numeric',
        month: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });
</script>

<script lang="ts">
    import type {Mail} from "$lib/repository/MailRepository";

    let {mail}: { mail: Mail } = $props();

    const sentAt = $derived(format.format(new Date(mail.sent_at)));
</script>

<time class="text-muted-foreground" datetime={mail.sent_at}>{sentAt}</time>
