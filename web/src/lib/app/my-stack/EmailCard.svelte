<script module lang="ts">
    /** One address as it stood in a header field; `name` is absent for a bare address. */
    export type EmailCardParticipant = {
        name?: string;
        address: string;
    };
</script>

<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {cn} from "$lib/utils.js";

    let {
        sender,
        sent,
        to,
        cc = [],
        bcc = [],
        subject,
        tags = [],
        body,
        dim = 0,
        tint = "transparent",
        class: className,
    }: {
        sender: EmailCardParticipant & { avatarUrl?: string };
        /** Formatted for display already: the card does no locale work of its own. */
        sent: string;
        to: EmailCardParticipant[];
        cc?: EmailCardParticipant[];
        bcc?: EmailCardParticipant[];
        subject: string;
        tags?: string[];
        /** Absent while the body's own request is still out; the card then shows its shape. */
        body?: string;
        /** 0…1: how far the card is faded into the background while it sits behind another one. */
        dim?: number;
        /** Colour washed over the whole card, e.g. the decision it was classified with. */
        tint?: string;
        class?: string;
    } = $props();

    const fields = $derived([
        {label: "To:", participants: to},
        {label: "CC:", participants: cc},
        {label: "BCC:", participants: bcc},
    ].filter((field) => field.participants.length > 0));

    function formatParticipant(participant: EmailCardParticipant): string {
        return participant.name ? `${participant.name} (${participant.address})` : participant.address;
    }

    /** Up to two initials, from the display name if there is one and the address otherwise. */
    const initials = $derived(
        (sender.name ?? sender.address)
            .split(/[\s.@_-]+/)
            .filter(Boolean)
            .slice(0, 2)
            .map((part) => part[0]!.toUpperCase())
            .join(""),
    );
</script>

<!-- box-shadow rather than drop-shadow: the card is an opaque rounded rectangle, so the cheaper
     one looks the same, and a filter would force its own render surface on every card in the
     stack. `class` goes last, so the caller can place and rotate the card in the stack. -->
<div class={cn("relative flex flex-col w-3xl h-fit bg-background rounded-2xl shadow-2xl", className)}>
    <div class="flex flex-row items-center justify-between gap-6 px-8 pt-8">
        <div class="flex flex-row gap-4 items-center">
            <Avatar.Root class="size-12">
                <Avatar.Image src={sender.avatarUrl} alt="" />
                <Avatar.Fallback class="text-base">{initials}</Avatar.Fallback>
            </Avatar.Root>
            <div class="flex flex-col">
                <span class="font-medium text-lg">{sender.name ?? sender.address}</span>
                {#if sender.name}
                    <span class="font-light text-base">{sender.address}</span>
                {/if}
            </div>
        </div>

        <div>
            <span class="font-light text-accent-foreground">{sent}</span>
        </div>
    </div>

    <div class="px-8 pt-4 flex flex-row flex-wrap items-center gap-x-10">
        {#each fields as field (field.label)}
            <div class="flex flex-row items-center gap-1">
                <span class="font-bold text-muted-foreground px-1 py-0.5 rounded-sm w-16">{field.label}</span>
                <span>{field.participants.map(formatParticipant).join(", ")}</span>
            </div>
        {/each}
    </div>

    <div class="px-8 pt-6 flex flex-row flex-wrap items-center gap-x-8 text-xl">
        {subject}
    </div>

    {#if tags.length}
        <div class="px-8 pt-3 flex flex-row flex-wrap items-center gap-1">
            {#each tags as tag (tag)}
                <span class="rounded-sm bg-muted px-2 py-0.5 text-sm text-muted-foreground">{tag}</span>
            {/each}
        </div>
    {/if}

    <div class="mx-4 my-4 h-px bg-accent"></div>

    {#if body === undefined}
        <!-- Lines in the shape of text rather than a spinner: everything above this is already
             there, so the card reads as one that is still filling in rather than as one that is
             loading. -->
        <div class="pb-8 px-8 flex flex-col gap-3">
            <Skeleton class="h-4 w-full" />
            <Skeleton class="h-4 w-11/12" />
            <Skeleton class="h-4 w-4/6" />
        </div>
    {:else}
        <div class="pb-8 px-8 whitespace-pre-wrap wrap-anywhere">
            {body}
        </div>
    {/if}

    <!-- Dimmed by laying the background colour on top rather than lowering the card's opacity: a
         card in the stack has to stay solid, or the cards behind it show through. -->
    <div
            class="pointer-events-none absolute inset-0 rounded-2xl bg-background transition-opacity duration-500 motion-reduce:transition-none"
            style="opacity: {dim}"
    ></div>

    <!-- The tint animates as a colour rather than as the opacity of a coloured layer, so it also
         washes back out when the mail is pulled into the stack again. Short: a mail that is being
         decided on is off the screen in a fraction of the time the card takes to travel, so a slow
         fade would never be seen. -->
    <div
            class="pointer-events-none absolute inset-0 rounded-2xl transition-colors duration-150 motion-reduce:transition-none"
            style="background-color: {tint}"
    ></div>
</div>
