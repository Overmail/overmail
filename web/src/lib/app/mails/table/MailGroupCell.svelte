<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import Stack from "phosphor-svelte/lib/Stack";
    import Tray from "phosphor-svelte/lib/Tray";
    import type {MailParticipant, MailThread} from "$lib/repository/MailRepository";
    import MailUserAvatar from "./MailUserAvatar.svelte";

    /** Past this the faces stop being recognisable and turn into a count. */
    const MAX_FACES = 5;

    /**
     * @param thread null for the mails nothing has filed, which are their own last group.
     * @param loaded how many of the group's mails this list holds.
     * @param total how many it has. Higher than [loaded] while the rest is still further back.
     * @param participants everyone on the group's mails, deduplicated. Empty for the unfiled group.
     */
    let {
        thread,
        loaded,
        total,
        participants
    }: {
        thread: MailThread | null;
        loaded: number;
        total: number;
        participants: MailParticipant[];
    } = $props();

    const faces = $derived(participants.slice(0, MAX_FACES));
    const hidden = $derived(participants.length - faces.length);
</script>

<div class="flex items-center gap-2">
    {#if thread}
        <Stack class="text-muted-foreground size-4 shrink-0" />
    {:else}
        <Tray class="text-muted-foreground size-4 shrink-0" />
    {/if}
    <span class="font-heading truncate text-sm">{thread?.title ?? 'Ohne Thread'}</span>
    <!-- Both numbers only when they disagree; "3 von 3" is noise. -->
    <span class="text-muted-foreground shrink-0 text-xs">
        {loaded === total ? total : `${loaded} von ${total}`}
    </span>

    {#if participants.length > 0}
        <!-- Who the exchange is between, in the order they joined it. -->
        <Avatar.Group class="shrink-0">
            {#each faces as participant (participant.address)}
                <MailUserAvatar {participant} />
            {/each}
            {#if hidden > 0}
                <Avatar.GroupCount class="text-xs">+{hidden}</Avatar.GroupCount>
            {/if}
        </Avatar.Group>
    {/if}
</div>
