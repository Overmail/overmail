<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import type {MailParticipant} from "$lib/repository/MailRepository";
    import {displayNameOf, initialsOf} from "../participant";

    /** @param size sm keeps the avatar at 24px, which is what holds a row within 32px. */
    let {
        participant,
        size = 'sm'
    }: { participant: MailParticipant; size?: 'default' | 'sm' | 'lg' } = $props();

    const displayName = $derived(displayNameOf(participant));
</script>

<!-- No picture to load: initials are the whole avatar until there is somewhere to fetch one from.
     The title is the only label a face in a thread header gets; the sender column has its own. -->
<Avatar.Root {size} title={displayName}>
    <Avatar.Fallback>{initialsOf(displayName)}</Avatar.Fallback>
</Avatar.Root>
