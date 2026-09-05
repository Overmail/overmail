<!--
    The route of a mail's own page: reads the url, and hands what it says to MailPage.
-->
<script lang="ts">
    import {page} from "$app/state";
    import {afterNavigate, goto} from "$app/navigation";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";
    import {FROM_MAIL_LIST, FROM_PARAM, parseEmailId} from "$lib/app/mails/emailPath";
    import MailPage from "$lib/app/mails/MailPage.svelte";

    const {mails} = useRepositories();

    /**
     * The mail this page is about. The subject in front of the id is for whoever reads the url;
     * only the id at the end of it says which mail, so a renamed subject in an old link still
     * lands here (see parseEmailId).
     */
    const id = $derived(parseEmailId(page.params.slug));

    /**
     * Whether the listing sent the reader here -- that is what the query says, and it is the one
     * case where this page has somewhere to go back to.
     */
    const cameFromMailList = $derived(page.url.searchParams.get(FROM_PARAM) === FROM_MAIL_LIST);

    /**
     * Whether there is an entry of this app behind this one. A navigation from within the app has
     * a [from]; opening the url straight -- a pasted link, a new tab -- has none, and stepping
     * back there would leave the app.
     */
    let hasEntryBehind = $state(false);
    afterNavigate((navigation) => hasEntryBehind = navigation.from !== null);

    /**
     * One step back, which is the list with this mail open beside it, and with the scroll position
     * the reader left it at -- neither of which a goto to the listing would bring back. Only when
     * there is such a step; without one the mailbox is where this mail came from.
     */
    function goBack() {
        if (hasEntryBehind) history.back();
        else void goto("/");
    }

    // For the title only; MailPage subscribes, and reads the same entry.
    const mail = $derived(id === null ? null : mails.peek(id).value);

    const title = $derived(
        mail === null
            ? $_("app.name")
            : `${mail.subject.trim() === "" ? $_("mails.noSubject") : mail.subject} • ${$_("app.name")}`
    );
</script>

<svelte:head><title>{title}</title></svelte:head>

<MailPage {id} {cameFromMailList} onBack={goBack}/>
