<script lang="ts">
    import {_} from "svelte-i18n";
    import HomeGreeting from "$lib/app/home/HomeGreeting.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import EmailGraph from "$lib/app/home/EmailGraph.svelte";
    import MailTable from "$lib/app/mails/MailTable.svelte";
    import {mailboxView} from "$lib/app/views/viewSettings";
    import {onMount} from "svelte";

    const {home} = useRepositories();

    // The socket is up while this page is: the effect's teardown releases it.
    $effect(() => home.connect());

    let currentUserName = $state<string | null>(null);
    let title = $derived.by(() => {
        let result = "Postfach"
        if (home.mailboxCount !== null) result += " (" + home.mailboxCount + ")";
        if (currentUserName) result += " • " + currentUserName;

        return result;
    })
    const {currentUser} = useRepositories();

    onMount(() => {
        currentUser.get().then((user) => {
            if (user) currentUserName = user.firstname + " " + user.lastname;
        })
    })

    /**
     * The listing this page shows. Its own, and only as long as the page: the mailbox is not a
     * view somebody saved, but it is set up like one, so the bar above it can be the same bar.
     */
    let mailbox = $state(mailboxView());

</script>

<svelte:head>
    <title>{title}</title>
</svelte:head>

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

    <div class="px-4 pt-12 pb-16">
        <MailTable bind:view={mailbox}/>
    </div>
</div>
