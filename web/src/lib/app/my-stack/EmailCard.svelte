<script module lang="ts">
    /** One address as it stood in a header field; `name` is absent for a bare address. */
    export type EmailCardParticipant = {
        name?: string;
        address: string;
    };
</script>

<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {cn} from "$lib/utils.js";

    let {
        sender,
        sent,
        to,
        cc = [],
        bcc = [],
        subject,
        body,
        class: className,
    }: {
        sender: EmailCardParticipant & { avatarUrl?: string };
        /** Formatted for display already: the card does no locale work of its own. */
        sent: string;
        to: EmailCardParticipant[];
        cc?: EmailCardParticipant[];
        bcc?: EmailCardParticipant[];
        subject: string;
        body: string;
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

<!-- `class` goes last, so the caller can place and rotate the card in the stack. -->
<div class={cn("flex flex-col w-3xl h-fit bg-background rounded-2xl drop-shadow-2xl", className)}>
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

    <div class="mx-4 my-4 h-px bg-accent"></div>

    <div class="pb-8 px-8 whitespace-pre-wrap wrap-anywhere">
        {body}
    </div>
</div>
