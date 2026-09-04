<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {_} from "svelte-i18n";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";

    let {mail}: {mail: EmailMeta} = $props();
</script>

{#if !mail.isRead}
    <Badge variant="secondary">{$_("mails.table.unread")}</Badge>
{:else if mail.archiveState === "archive"}
    <!-- The list holds archived mails too, so it says which ones they are. -->
    <Badge variant="outline">{$_("mails.table.archived")}</Badge>
{/if}
