<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import type {Mail} from "$lib/repository/MailRepository";

    let {mail}: { mail: Mail } = $props();
</script>

{#if !mail.is_read}
    <Badge variant="secondary">Ungelesen</Badge>
{/if}
