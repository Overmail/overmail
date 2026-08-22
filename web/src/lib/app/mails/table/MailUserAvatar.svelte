<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import type {MailParticipant} from "$lib/repository/MailRepository";
    import {avatarStore} from "$lib/app/avatars/AvatarStore.svelte";
    import {displayNameOf, initialsOf} from "../participant";

    /** @param size sm keeps the avatar at 24px, which is what holds a row within 32px. */
    let {
        participant,
        size = 'sm'
    }: { participant: MailParticipant; size?: 'default' | 'sm' | 'lg' } = $props();

    const displayName = $derived(displayNameOf(participant));

    // Asked for here rather than by the page: an avatar is rendered by the table itself, so this
    // is the only place that knows one is wanted. The store reads the list once however many rows
    // ask for it.
    $effect(() => avatarStore.ensureLoaded());

    // Null for an address no picture is held for, which is most of them -- see AvatarStore.
    const src = $derived(avatarStore.urlFor(participant.address));
</script>

<!-- The title is the only label a face in a thread header gets; the sender column has its own. -->
<Avatar.Root {size} title={displayName}>
    {#if src}
        <!-- lazy, so scrolling a long list does not fire a request per row up front. -->
        <Avatar.Image {src} alt={displayName} loading="lazy" />
    {/if}
    <!-- Also what shows while the picture is still loading, and if it fails to. -->
    <Avatar.Fallback>{initialsOf(displayName)}</Avatar.Fallback>
</Avatar.Root>
