<!--
    One mail on a page of its own: what the panel beside the list shows, with a bar that has no
    list to step through, nothing to close and nowhere left to open.
-->
<script lang="ts">
    import {untrack} from "svelte";
    import {page} from "$app/state";
    import {_} from "svelte-i18n";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {useRepositories} from "$lib/repository/repositories";
    import {parseEmailId} from "$lib/app/mails/emailPath";
    import Head from "$lib/app/mails/detail_panel/Head.svelte";
    import Detail from "$lib/app/mails/detail_panel/Detail.svelte";

    const {mails} = useRepositories();

    /**
     * The mail this page is about. The subject in front of the id is for whoever reads the url;
     * only the id at the end of it says which mail, so a renamed subject in an old link still
     * lands here (see parseEmailId).
     */
    const id = $derived(parseEmailId(page.params.slug));

    // Kept up to date for as long as the page is open, exactly like a row of the table.
    $effect(() => {
        if (id === null) return;
        return mails.subscribe(id);
    });

    // Opening a mail is reading it, wherever it is opened. Untracked, or the answer coming back
    // over the socket would run this again; see the panel, which does the same.
    $effect(() => {
        const opened = id;
        if (opened === null) return;

        untrack(() => void mails.setRead(opened, true));
    });

    const entry = $derived(id === null ? null : mails.peek(id));
    const mail = $derived(entry?.value ?? null);

    const title = $derived(
        mail === null
            ? $_("app.name")
            : `${mail.subject.trim() === "" ? $_("mails.noSubject") : mail.subject} • ${$_("app.name")}`
    );
</script>

<svelte:head><title>{title}</title></svelte:head>

<div class="flex w-full min-w-0 flex-col gap-6 py-4">
    {#if mail !== null}
        <!-- The subject is the heading of this page, and the tools belong to the same mail: one
             line, with the space between them rather than under them. min-w-0 so a long subject
             is cut instead of pushing the buttons off the edge. -->
        <div class="flex flex-row items-center justify-between gap-4 px-6">
            <h1 class="min-w-0 font-display text-2xl text-pretty">{mail.subject}</h1>

            <Head
                    class="w-auto shrink-0 px-0"
                    mail={mail}
                    showOpenInNewTab={false}
                    onChangeArchiveState={(newState) => mails.setArchiveState(mail.id, newState)}
                    onShareMail={() => alert("Sharing is not yet supported. Note that sharing a mail is not the same as forwarding it.")}
                    onChangeReadState={(isRead) => mails.setRead(mail.id, isRead)}
                    onReclassify={() => mails.requestClassification(mail.id)}
            />
        </div>

        <Detail {mail} showSubject={false}/>
    {:else if entry?.isLoading}
        <div class="flex flex-col gap-4 px-6">
            <Skeleton class="h-8 w-2/3"/>
            <Skeleton class="h-10 w-1/2"/>
            <Skeleton class="h-4 w-full"/>
            <Skeleton class="h-4 w-10/12"/>
        </div>
    {:else}
        <!-- A mail that is not ours, is gone, or an id that never was one: told apart nowhere,
             the same as everywhere else in this app. -->
        <p class="px-6 text-sm text-muted-foreground">{$_("mails.panel.missing")}</p>
    {/if}
</div>
