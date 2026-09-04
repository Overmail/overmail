<script lang="ts">
    import {_} from "svelte-i18n";
    import HomeGreeting from "$lib/app/home/HomeGreeting.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import EmailGraph from "$lib/app/home/EmailGraph.svelte";
    import MailTable from "$lib/app/mails/MailTable.svelte";

    const {home} = useRepositories();

    // The socket is up while this page is: the effect's teardown releases it.
    $effect(() => home.connect());
</script>

<div class="flex flex-col">
    <div class="flex flex-row flex-wrap px-16 pt-16 gap-x-16 gap-y-8">
        <div class="flex flex-col gap-2">
            <HomeGreeting/>
            {#if home.mailboxCount !== null}
                <h2 class="text-muted-foreground">
                    {$_("home.mailbox.count", {values: {count: home.mailboxCount}})}
                </h2>
            {:else}
                <Skeleton class="h-5 w-56"/>
            {/if}
        </div>

        <EmailGraph/>
    </div>

    <div class="px-16 pt-12 pb-16">
        <MailTable/>
    </div>
</div>
