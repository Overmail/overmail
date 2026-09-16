<script lang="ts">
    import {_} from "svelte-i18n";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import MailBody from "$lib/app/mails/detail_panel/MailBody.svelte";
    import {useRepositories} from "$lib/repository/repositories";

    let {id}: {id: string} = $props();

    const {emailBody} = useRepositories();

    type MailBodyParts = {text: string | null; html: string | null};

    /** What came back, with the mail it is for: an answer for the mail before is never shown. */
    let loaded = $state<{id: string; body: MailBodyParts} | null>(null);
    let failedId = $state<string | null>(null);

    // Read from the cache on the first render: the panel and the page are separate mounts, and a
    // body that is already here must not go through the skeleton again on the way between them.
    const body = $derived(loaded?.id === id ? loaded.body : emailBody.peek(id));
    const failed = $derived(failedId === id);

    // One request per mail, and the answer of the mail before it is dropped rather than shown:
    // stepping through a list is faster than a body comes back.
    $effect(() => {
        const current = id;
        if (emailBody.peek(current) !== null) return;

        let live = true;
        emailBody
            .getBody(current)
            .then((result) => {
                if (live) loaded = {id: current, body: result};
            })
            .catch((error) => {
                console.error(error);
                if (live) failedId = current;
            });

        return () => (live = false);
    });
</script>

<div class="flex min-w-0 flex-col gap-3 px-6">
    {#if body === null && !failed}
        <!-- A body is one request away; the shape of a few lines says that it is coming. -->
        <Skeleton class="h-4 w-full"/>
        <Skeleton class="h-4 w-11/12"/>
        <Skeleton class="h-4 w-8/12"/>
    {:else if failed}
        <p class="text-sm text-muted-foreground">{$_("mails.content.failed")}</p>
    {:else if body}
        <!-- Keyed on the mail: the next one is read the way a mail is meant to be read, whatever
             tab was picked for the one before it. -->
        {#key id}
            <MailBody text={body.text} html={body.html} />
        {/key}
    {/if}
</div>
