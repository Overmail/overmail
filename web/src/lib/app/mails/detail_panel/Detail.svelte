<!--
    Everything a mail is, without the frame around it: what it says, who it is between, and how it
    is sorted. Not the subject: the panel and the page both put that above, each in its own way.

    Its own component because two places show it -- the panel beside the list and the mail's own
    page -- and only what is above it differs between them. That is also what lets the browser
    carry it from the one place to the other (see mailViewTransition): the same thing at both ends,
    beginning with the same line.
-->
<script lang="ts">
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import Participants from "$lib/app/home/Participants.svelte";
    import Labels from "$lib/app/mails/detail_panel/Labels.svelte";
    import Content from "$lib/app/mails/detail_panel/Content.svelte";
    import {MAIL_DETAIL_TRANSITION} from "$lib/app/mails/mailViewTransition";

    let {mail}: {mail: EmailMeta} = $props();

    const {mails} = useRepositories();
</script>

<div class="flex min-w-0 flex-col gap-6">
    <!-- Named: the panel and the page both render this, so across the navigation between them the
         browser carries it from the one place to the other. Only who and what, not the body -- the
         body arrives after the first paint and grows the block while the morph runs, and Firefox
         keeps a snapshot at the size it had when the morph began. See mailViewTransition. -->
    <div style:view-transition-name={MAIL_DETAIL_TRANSITION} class="flex min-w-0 flex-col gap-6">
        <Participants
                from={mail.sender}
                to={mail.to}
                cc={mail.cc}
                bcc={mail.bcc}
            sentAt={new Date(mail.sent * 1000)}
        />

        <Labels
                labels={mail.labels}
                onAddLabel={(label) => mails.attachLabel(mail.id, label.id)}
                onCreateLabel={(name) => mails.createLabelOn(mail.id, name)}
                onRemoveLabel={(label) => mails.detachLabel(mail.id, label.id)}
        />
    </div>

    <Content id={mail.id}/>
</div>
