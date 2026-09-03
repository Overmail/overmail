<script lang="ts">
    import {_} from "svelte-i18n";
    import HomeGreeting from "$lib/app/home/HomeGreeting.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {Skeleton} from "$lib/components/ui/skeleton";

    const {home} = useRepositories();

    // The socket is up while this page is: the effect's teardown releases it.
    $effect(() => home.connect());
</script>

<div class="flex flex-col">
    <div class="flex flex-col px-16 pt-16 gap-2">
        <HomeGreeting/>
        {#if home.mailboxCount !== null}
            <h2 class="text-muted-foreground">
                {$_("home.mailbox.count", {values: {count: home.mailboxCount}})}
            </h2>
        {:else}
            <Skeleton class="h-5 w-56"/>
        {/if}
    </div>
</div>
