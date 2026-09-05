<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import type {EmailParticipant} from "$lib/repository/EmailRepository.svelte";
    import type {CurrentUser} from "$lib/repository/CurrentUserRepository";
    import {OvermailAvatar} from "$lib/components/avatar";
    import {displayName, isSelf} from "$lib/app/mails/participants";
    import {useRepositories} from "$lib/repository/repositories";
    import RelativeTime from "$lib/components/time/RelativeTime.svelte";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import { ArrowBendDownRightIcon } from "phosphor-svelte";

    let {
        from,
        to,
        cc,
        bcc,
        sentAt,
    }: {
        from: EmailParticipant,
        to: EmailParticipant[],
        cc: EmailParticipant[],
        bcc: EmailParticipant[],
        sentAt: Date,
    } = $props();

    const {currentUser} = useRepositories();

    // Null until the answer is in, which is one cached request: until then everybody is named,
    // the reader included, and the line only gets shorter afterwards.
    let me = $state<CurrentUser | null>(null);

    onMount(() => {
        currentUser.get().then((user) => (me = user));
    });

    /** The groups that have anybody in them, in the order a header is read. */
    const groups = $derived(
        [
            {key: "mails.participants.to", people: to},
            {key: "mails.participants.cc", people: cc},
            {key: "mails.participants.bcc", people: bcc},
        ].filter((group) => group.people.length > 0)
    );

    /** The reader is not a name in their own mail: "an dich" is what they would say about it. */
    const nameOf = (participant: EmailParticipant) =>
        isSelf(participant, me) ? $_("mails.participants.you") : displayName(participant);
</script>

<div class="flex flex-col px-6.5 gap-3">
    <div class="flex flex-row gap-4 items-center justify-between">
        <div class="flex flex-row gap-4 items-center">
            <OvermailAvatar
                    url={from.avatarUrl}
                    name={displayName(from)}
                    class="size-9"
                    fallbackClass="text-[9px]"
            />

            <div class="flex flex-col">
                {#if from.name}
                    <span class="font-medium leading-4.5">{from.name}</span>
                {/if}
                <span class="text-sm text-muted-foreground">{from.address}</span>
            </div>
        </div>

        <div>
            <span class="text-sm text-muted-foreground">
                <Tooltip.Root>
                    <Tooltip.Trigger>
                        <RelativeTime date={sentAt} />
                    </Tooltip.Trigger>

                    <Tooltip.Content>
                        {sentAt.toLocaleString()}
                    </Tooltip.Content>
                </Tooltip.Root>
            </span>
        </div>
    </div>

    <div class="flex flex-col gap-1">
        {#each groups as group (group.key)}
            <div class="flex min-w-0 flex-row flex-wrap items-center gap-x-2 gap-y-1 text-sm pl-2.5">
                <ArrowBendDownRightIcon />
                <span class="shrink-0 text-muted-foreground font-medium">{$_(group.key)}</span>

                <ul class="flex min-w-0 flex-row flex-wrap items-center gap-x-2 gap-y-1">
                    {#each group.people as person, index (person.id)}
                        <li class="flex min-w-0 items-center gap-1" title={person.address}>
                            <OvermailAvatar
                                    url={person.avatarUrl}
                                    name={displayName(person)}
                                    class="size-5 shrink-0"
                                    fallbackClass="text-[9px]"
                            />
                            <span class="truncate">{nameOf(person)}{index == group.people.length - 1 ? '' : ','}</span>
                        </li>
                    {/each}
                </ul>
            </div>
        {/each}
    </div>
</div>