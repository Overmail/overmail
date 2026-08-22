<script lang="ts">
    import * as Sidebar from "$lib/components/ui/sidebar";
    import {Separator} from "$lib/components/ui/separator";
    import {Button} from "$lib/components/ui/button";
    import EmailGraph from "$lib/app/home/EmailGraph.svelte";
    import AgentReset from "$lib/app/home/AgentReset.svelte";
    import AgentCard from "$lib/app/agent/AgentCard.svelte";
    import AvatarCache from "$lib/app/home/AvatarCache.svelte";
    import MailTable from "$lib/app/mails/MailTable.svelte";
    import {MailStore} from "$lib/app/mails/MailStore.svelte";
    import {ThreadedMailStore} from "$lib/app/mails/ThreadedMailStore.svelte";

    // Owned by the page rather than shared from a module: a module-level instance would be one
    // list for every server-rendered request. A live connection takes this instance instead.
    const mails = new MailStore();
    // The two arrangements page in completely different ways -- flat by position, grouped off the
    // thread skeleton -- so each keeps its own state and neither has to undo the other's.
    const threadedMails = new ThreadedMailStore();
</script>

<header
        class="flex shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear h-12"
>
    <div class="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <Sidebar.Trigger class="-ms-1" />
        <Separator orientation="vertical" class="mx-2 data-[orientation=vertical]:h-4" />
        <h1 class="text-base font-medium">Home</h1>
        <div class="ms-auto flex items-center gap-2">
            <Button
                    href="https://github.com/overmail"
                    variant="ghost"
                    size="sm"
                    class="hidden sm:flex dark:text-foreground"
                    target="_blank"
                    rel="noopener noreferrer"
            >
                GitHub
            </Button>
        </div>
    </div>
</header>

<main class="flex flex-1 flex-col gap-10 p-4 lg:p-6">
    <EmailGraph />
    <AgentCard />
    <AgentReset />
    <AvatarCache />
    <MailTable store={mails} threaded={threadedMails} />
</main>
