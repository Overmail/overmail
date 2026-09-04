<script lang="ts">
    import {OvermailAvatar} from "$lib/components/avatar";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu/index.js";
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";
    import {cn} from "$lib/utils.js";
    import type {StackEmail} from "$lib/app/my-stack/EmailStackViewModel.svelte";
    import type {EmailLabel} from "$lib/repository/EmailRepository.svelte";
    import {displayName, spelledOut} from "$lib/app/mails/participants";
    import {Button} from "$lib/components/ui/button";
    import { DotsThreeVerticalIcon } from "phosphor-svelte";
    import {getStackFocus} from "$lib/app/my-stack/stackFocus";
    import {_} from "svelte-i18n";
    import {onMount} from "svelte";

    let {
        sent,
        sender,
        to,
        cc,
        bcc,
        subject,
        body,
        labels,
        class: className,
        onRequestReclassify,
        onReady,
    }: StackEmail & {
        class?: string;
        onRequestReclassify: () => Promise<boolean>;
        /** Fires once the card is laid out and worth showing; see below. */
        onReady?: () => void;
    } = $props();

    const stackFocus = getStackFocus();

    /**
     * How long a body gets to lay itself out before the card is shown anyway. Whoever is waiting
     * on this is waiting to show the card, so a mail whose iframe never reports back has to end
     * up on the pile regardless -- late and complete beats never.
     */
    const READY_TIMEOUT = 2000;

    let reported = false;

    function reportReady() {
        if (reported) return;

        reported = true;
        onReady?.();
    }

    // An HTML body is only worth showing once the iframe has measured itself, and that is the one
    // thing here that takes a moment; everything else is laid out as soon as it is mounted.
    onMount(() => {
        if (!body.html) {
            reportReady();
            return;
        }

        const timer = setTimeout(reportReady, READY_TIMEOUT);
        return () => clearTimeout(timer);
    });

    const fields = $derived([
        {key: "myStack.email.to", participants: to},
        {key: "myStack.email.cc", participants: cc},
        {key: "myStack.email.bcc", participants: bcc},
    ].filter((field) => field.participants.length > 0));

</script>

<!-- `class` goes last, so the caller can place and rotate the card in the stack. The width comes
     from the stack rather than from here: the card fills the column it is laid into, so a narrow
     screen gets a narrow card instead of one that reaches past the edge of it. -->
<div class={cn("flex flex-col w-full h-fit bg-background rounded-2xl drop-shadow-2xl", className)}>
    <!-- min-w-0 down this row: a flex item does not shrink below its content on its own, so a
         long address or a spelled-out date would push the card wider than the column it is in. -->
    <div class="flex flex-row items-center justify-between gap-6 px-4 pt-8 sm:px-8">
        <div class="flex min-w-0 flex-row gap-4 items-center">
            <OvermailAvatar
                    url={sender.avatarUrl}
                    name={displayName(sender)}
                    class="size-12"
                    fallbackClass="text-base"
            />
            <div class="flex min-w-0 flex-col">
                <span class="truncate font-medium text-lg">{displayName(sender)}</span>
                {#if sender.name}
                    <span class="truncate font-light text-base">{sender.address}</span>
                {/if}
            </div>
        </div>

        <div class="flex shrink-0 flex-row items-center gap-1">
            <!-- Unix seconds off the wire; the card is the only place that needs them as a date. -->
            <span class="font-light text-accent-foreground">{new Date(sent * 1000).toLocaleString()}</span>

            <DropdownMenu.Root>
                <DropdownMenu.Trigger>
                    <!-- child, so the trigger *is* the button: rendering one inside the other
                         nests two <button> elements, which is invalid and makes the key handling
                         of both fire. -->
                    {#snippet child({props})}
                        <Button
                                {...props}
                                variant="ghost"
                                size="icon"
                        >
                            <DotsThreeVerticalIcon />
                        </Button>
                    {/snippet}
                </DropdownMenu.Trigger>

                <!-- The keyboard goes back to the stack rather than to this button, which would
                     answer to the next Space itself instead of letting the mail move on. -->
                <DropdownMenu.Content
                        class="w-56"
                        align="start"
                        onCloseAutoFocus={(event) => {
                            if (!stackFocus) return;

                            event.preventDefault();
                            stackFocus.restore();
                        }}
                >
                    <DropdownMenu.Label>{$_('myStack.email.menu.ai')}</DropdownMenu.Label>
                    <DropdownMenu.Group>
                        <DropdownMenu.Item onclick={onRequestReclassify}>
                            {$_('myStack.email.menu.reclassify')}
                        </DropdownMenu.Item>
                    </DropdownMenu.Group>
                </DropdownMenu.Content>
            </DropdownMenu.Root>
        </div>
    </div>

    <div class="px-4 pt-4 flex flex-row flex-wrap items-center gap-x-10 sm:px-8">
        {#each fields as field (field.key)}
            <div class="flex min-w-0 flex-row items-center gap-1">
                <span class="font-bold text-muted-foreground px-1 py-0.5 rounded-sm w-16 shrink-0">{$_(field.key)}</span>
                <span class="min-w-0 wrap-anywhere">{field.participants.map(spelledOut).join(", ")}</span>
            </div>
        {/each}
    </div>

    <div class="px-4 pt-6 flex flex-row flex-wrap items-center gap-x-8 text-xl wrap-anywhere sm:px-8">
        {subject}
    </div>

    <!-- Chips carry the label color as a dot and a subtle tint; the text keeps the theme's
         foreground color so any label color stays readable in light and dark mode. -->
    {#snippet chip(label: EmailLabel, props: Record<string, unknown> = {})}
        <span
                {...props}
                class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium"
                style={`background-color: color-mix(in srgb, ${label.color} 12%, transparent); border-color: color-mix(in srgb, ${label.color} 35%, transparent);`}
        >
            <span class="size-1.5 shrink-0 rounded-full" style={`background-color: ${label.color};`}></span>
            {label.name}
        </span>
    {/snippet}

    <div class="px-4 pt-3 flex flex-row flex-wrap items-center gap-1.5 sm:px-8">
        {#each labels as label (label.id)}
            {#if label.description || label.assignmentReason}
                <Tooltip.Root>
                    <Tooltip.Trigger>
                        {#snippet child({props})}
                            {@render chip(label, props)}
                        {/snippet}
                    </Tooltip.Trigger>
                    <Tooltip.Content side="bottom" class="flex-col items-start gap-0.5">
                        {#if label.description}
                            <p>{label.description}</p>
                        {/if}
                        {#if label.assignmentReason}
                            <p class="text-background/70">{label.assignmentReason}</p>
                        {/if}
                    </Tooltip.Content>
                </Tooltip.Root>
            {:else}
                {@render chip(label)}
            {/if}
        {/each}
    </div>

    <div class="mx-4 my-4 h-px bg-accent"></div>

    <div class="pb-8 px-4 whitespace-pre-wrap wrap-anywhere sm:px-8">
        {#if body.html}
            <EmailHtmlBody html={body.html} onReady={reportReady}/>
        {:else if body.text}
            {body.text}
        {:else}
            <span class="text-muted-foreground">{$_('myStack.email.noContent')}</span>
        {/if}
    </div>
</div>
