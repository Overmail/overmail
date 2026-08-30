<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";
    import {cn} from "$lib/utils.js";
    import type {EmailUser, Label, StackEmail} from "$lib/app/my-stack/EmailStackViewModel.svelte";

    let {
        sent_at,
        from,
        to,
        cc,
        bcc,
        subject,
        body,
        labels,
        class: className,
    }: StackEmail & {
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
                <Avatar.Image src={from.avatarUrl} alt=""/>
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
            <span class="font-light text-accent-foreground">{sent_at.toLocaleString()}</span>
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

    <!-- Chips carry the label color as a dot and a subtle tint; the text keeps the theme's
         foreground color so any label color stays readable in light and dark mode. -->
    {#snippet chip(label: Label, props: Record<string, unknown> = {})}
        <span
                {...props}
                class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium"
                style={`background-color: color-mix(in srgb, ${label.color} 12%, transparent); border-color: color-mix(in srgb, ${label.color} 35%, transparent);`}
        >
            <span class="size-1.5 shrink-0 rounded-full" style={`background-color: ${label.color};`}></span>
            {label.name}
        </span>
    {/snippet}

    <div class="px-8 pt-3 flex flex-row flex-wrap items-center gap-1.5">
        {#each labels as label (label.id)}
            {#if label.label_description || label.assignment_reason}
                <Tooltip.Root>
                    <Tooltip.Trigger>
                        {#snippet child({props})}
                            {@render chip(label, props)}
                        {/snippet}
                    </Tooltip.Trigger>
                    <Tooltip.Content side="bottom" class="flex-col items-start gap-0.5">
                        {#if label.label_description}
                            <p>{label.label_description}</p>
                        {/if}
                        {#if label.assignment_reason}
                            <p class="text-background/70">{label.assignment_reason}</p>
                        {/if}
                    </Tooltip.Content>
                </Tooltip.Root>
            {:else}
                {@render chip(label)}
            {/if}
        {/each}
    </div>

    <div class="mx-4 my-4 h-px bg-accent"></div>

    <div class="pb-8 px-8 whitespace-pre-wrap wrap-anywhere">
        {#if body.html}
            <EmailHtmlBody html={body.html}/>
        {:else if body.text}
            {body.text}
        {:else}
            <span class="text-muted-foreground">No content</span>
        {/if}
    </div>
</div>
