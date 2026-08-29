<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";
    import {cn} from "$lib/utils.js";
    import type {EmailUser, StackEmail} from "$lib/app/my-stack/EmailStackViewModel.svelte";

    let {
        sent_at,
        from,
        to,
        cc,
        bcc,
        subject,
        body,
        class: className,
    }: StackEmail &{
        class?: string;
    } = $props();

    const fields = $derived([
        {label: "To:", participants: to},
        {label: "CC:", participants: cc},
        {label: "BCC:", participants: bcc},
    ].filter((field) => field.participants.length > 0));

    function formatParticipant(participant: EmailUser): string {
        return participant.name ? `${participant.name} (${participant.email})` : participant.email;
    }

    /** Up to two initials, from the display name if there is one and the address otherwise. */
    const initials = $derived(
        (from.name ?? from.email)
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
                <Avatar.Image src={from.avatarUrl} alt="" />
                <Avatar.Fallback class="text-base">{initials}</Avatar.Fallback>
            </Avatar.Root>
            <div class="flex flex-col">
                <span class="font-medium text-lg">{from.name ?? from.email}</span>
                {#if from.name}
                    <span class="font-light text-base">{from.email}</span>
                {/if}
            </div>
        </div>

        <div>
            <span class="font-light text-accent-foreground">{sent_at}</span>
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
        {#if body.html}
            <EmailHtmlBody html={body.html} />
        {:else if body.text}
            {body.text}
        {:else}
            <span class="text-muted-foreground">No content</span>
        {/if}
    </div>
</div>
