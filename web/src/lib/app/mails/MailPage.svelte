<!--
    One mail on a page of its own: what the panel beside the list shows, with a bar that has no
    list to step through, nothing to close and nowhere left to open.

    Its own component rather than the route itself, so it can be rendered next to the panel in a
    harness -- the morph between the two (see mailViewTransition) is tuned against both ends, and
    the route only knows the url.
-->
<script lang="ts">
    import {untrack} from "svelte";
    import {_} from "svelte-i18n";
    import {ArrowLeftIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {useRepositories} from "$lib/repository/repositories";
    import {MAIL_BOX_TRANSITION, MAIL_SUBJECT_TRANSITION} from "$lib/app/mails/mailViewTransition";
    import Head from "$lib/app/mails/detail_panel/Head.svelte";
    import Detail from "$lib/app/mails/detail_panel/Detail.svelte";

    let {
        id,
        cameFromMailList,
        onBack,
    }: {
        /** The mail this page is about; null for a url that never named one. */
        id: string | null;
        /**
         * Whether the listing sent the reader here. It is the one case where this page has
         * somewhere to go back to, and the one case where it is the other end of the morph.
         */
        cameFromMailList: boolean;
        onBack: () => void;
    } = $props();

    const {mails} = useRepositories();

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
</script>

<!-- The other end of the morph: this column is what the panel grows into, and the heading below
     is what its subject moves to. Only while the listing is behind this page -- a mail opened from
     a link has nothing to grow out of, and a name costs nothing then. See mailViewTransition.

     With a background of its own, although the page behind it has the same one: the snapshot of a
     see-through column would let the list show through the mail while the column grows over it.
     And as tall as the page allows (flex-1 in the shell's column), so the box the morph heads for
     has its size before the mail's body has arrived -- the body comes in after the first paint. -->
<div
        style:view-transition-name={cameFromMailList ? MAIL_BOX_TRANSITION : undefined}
        class="flex w-full min-w-0 flex-1 flex-col gap-6 bg-background py-4"
>
    <!-- Above everything, whether the mail is here yet or not: a page that was opened out of the
         list is one the reader means to leave again. -->
    {#if cameFromMailList}
        <div class="px-6">
            <Button variant="ghost" size="sm" class="-ml-2 gap-2" onclick={onBack}>
                <ArrowLeftIcon/>
                {$_("mails.page.back")}
            </Button>
        </div>
    {/if}

    {#if mail !== null}
        <!-- The subject is the heading of this page, and the tools belong to the same mail: one
             line, with the space between them rather than under them. min-w-0 so a long subject
             is cut instead of pushing the buttons off the edge. -->
        <div class="flex flex-row items-center justify-between gap-4 px-6">
            <h1
                    style:view-transition-name={cameFromMailList ? MAIL_SUBJECT_TRANSITION : undefined}
                    class="min-w-0 font-display text-2xl text-pretty"
            >{mail.subject}</h1>

            <Head
                    class="w-auto shrink-0 px-0"
                    mail={mail}
                    showOpenInNewTab={false}
                    onChangeArchiveState={(newState) => mails.setArchiveState(mail.id, newState)}
                    onShareMail={() => alert("Sharing is not yet supported. Note that sharing a mail is not the same as forwarding it.")}
                    onChangeReadState={(isRead) => mails.setRead(mail.id, isRead)}
                    onDownloadMail={() => mails.downloadMail(mail.id)}
                    onReclassify={() => mails.requestClassification(mail.id)}
            />
        </div>

        <Detail {mail}/>
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
