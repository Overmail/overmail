<!--
    The face of a mail and the checkbox for it, in the one spot both of them want.

    Which of the two is shown is the cursor and the tick, and nothing else: the row is the group,
    so hovering it swaps them in CSS -- a windowed table that ran a handler per row to know it is
    hovered would do that work over and over while somebody scrolls -- and a mail that has been
    picked keeps its box out. So a list in selection mode is the picked mails with their boxes
    among the faces of the rest, rather than a column of empty squares.

    See selectionReveal for the classes and for what "shown" means for a click.
-->
<script lang="ts">
    import {_} from "svelte-i18n";
    import {Checkbox} from "$lib/components/ui/checkbox";
    import {cn} from "$lib/utils";
    import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
    import {getMailSelection} from "$lib/app/mails/mailSelection";
    import {AVATAR_REVEAL, CHECKBOX_REVEAL, HIDDEN, SHOWN} from "./selectionReveal";
    import MailUserAvatar from "./MailUserAvatar.svelte";

    let {mail}: {mail: EmailMeta} = $props();

    const selection = getMailSelection();

    const selected = $derived(selection?.has(mail.id) ?? false);

    /** How the box says which mail it is about, which for a reader is the subject. */
    const label = $derived(
        $_("mails.selection.select", {values: {subject: mail.subject || $_("mails.noSubject")}})
    );
</script>

{#if selection === null}
    <MailUserAvatar participant={mail.sender}/>
{:else}
    <!-- Both in the same grid cell rather than one lifted out of the flow over the other: the
         column keeps the avatar's width either way, so nothing shifts as they swap. -->
    <div class="grid size-5 shrink-0 place-items-center">
        <div class={cn("col-start-1 row-start-1", AVATAR_REVEAL, selected && HIDDEN)}>
            <MailUserAvatar participant={mail.sender}/>
        </div>

        <!-- The click is the row's as well, and the row opens the mail: ticking a box is not
             asking to read it. The hit area a checkbox brings along is cut back to the avatar's
             surroundings, or it would reach into the sender's name beside it. -->
        <Checkbox
                aria-label={label}
                bind:checked={() => selected, (value) => selection.set(mail.id, value)}
                onclick={(event: MouseEvent) => event.stopPropagation()}
                ondblclick={(event: MouseEvent) => event.stopPropagation()}
                class={cn("col-start-1 row-start-1", CHECKBOX_REVEAL, selected && SHOWN)}
        />
    </div>
{/if}
