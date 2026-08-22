<script lang="ts">
    import type {Mail} from "$lib/repository/MailRepository";
    import MailTagBadges from "./MailTagBadges.svelte";

    let {mail}: { mail: Mail } = $props();

    const subject = $derived(mail.subject.trim());
</script>

<!-- The subject is the column that absorbs the leftover width, so the badges have room. -->
<div class="flex min-w-0 items-center gap-2">
    <span class="truncate" class:text-muted-foreground={subject === ''}>
        {subject || '(kein Betreff)'}
    </span>
    <MailTagBadges tags={mail.tags} />
</div>
