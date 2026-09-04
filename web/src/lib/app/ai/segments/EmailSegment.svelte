<script lang="ts">
    import {EnvelopeSimpleIcon} from "phosphor-svelte";
    import {OvermailAvatar} from "$lib/components/avatar";
    import * as HoverCard from "$lib/components/ui/hover-card";
    import EmailPreviewCard from "$lib/app/mails/EmailPreviewCard.svelte";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {useRepositories} from "$lib/repository/repositories";
    import {emailPath} from "$lib/app/mails/emailPath";
    import {goto} from "$app/navigation";
    import type {PromptEmail} from "$lib/app/ai/composer/prompt";
    import {shortSubject} from "$lib/app/ai/emailSubject";
    import {_} from "svelte-i18n";

    let {
        email,
    }: {
        email: PromptEmail;
    } = $props();

    const {mails} = useRepositories();

    let open = $state(false);

    // Subscribed only while the card is up: a chat mentioning a dozen mails would otherwise put
    // all of them on the content socket for a preview nobody asked to see.
    $effect(() => {
        if (open) return mails.subscribe(email.id);
    });

    const entry = $derived(open ? mails.peek(email.id) : null);

    /**
     * A single click on a chip is not an intent -- it is where the caret goes in the prompt
     * editor, or a stray click in a message. The second one opens the mail.
     */
    function openMail(event: Event) {
        // In the editor the browser would select the chip's text on a double click, and in a
        // message the click would reach whatever the chip sits in.
        event.preventDefault();
        event.stopPropagation();
        goto(emailPath(email.id, email.subject));
    }
</script>

<HoverCard.Root bind:open openDelay={400}>
    <HoverCard.Trigger>
        <!-- child, so the chip stays the one inline span it was: the trigger renders an anchor of
             its own otherwise, and this sits in a line of text and inside the prompt editor. -->
        {#snippet child({props})}
            <!-- role and tabindex: the trigger brings the aria of something that has a popup, and
                 a span is neither focusable nor interactive on its own -- without these the
                 preview is mouse-only. The role is the one the trigger sets itself; spelled out
                 here because a spread is not something the compiler can check tabindex against. -->
            <span
                    {...props}
                    role="button"
                    tabindex={0}
                    ondblclick={openMail}
                    onkeydown={(event) => {
                        // The keyboard's way to the same place. Kept off the prompt editor
                        // underneath, which answers to Enter by sending what has been typed.
                        if (event.key === "Enter" || event.key === " ") openMail(event);
                    }}
                    class="inline whitespace-nowrap bg-card text-card-foreground px-1 mx-0.5 rounded outline focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-ring"
            >
                <OvermailAvatar
                        inline
                        url={email.avatarUrl}
                        class="size-3.5"
                >
                    {#snippet fallback()}<EnvelopeSimpleIcon class="size-full"/>{/snippet}
                </OvermailAvatar>
                {shortSubject(email.subject)}
            </span>
        {/snippet}
    </HoverCard.Trigger>

    <!-- The card brings its own frame, so the content is only the box it hangs in. -->
    <HoverCard.Content class="w-80 overflow-hidden rounded-xl p-0" side="top">
        {#if entry?.value}
            <EmailPreviewCard mail={entry.value} class="rounded-none border-none"/>
        {:else if entry?.isLoading}
            <!-- The subject is what the chip already carries, so the wait is only about the rest. -->
            <div class="flex flex-col gap-2 p-3">
                <Skeleton class="h-24 w-full rounded-lg"/>
                <span class="line-clamp-2 text-sm font-medium wrap-anywhere">{email.subject}</span>
                <Skeleton class="h-4 w-40 rounded"/>
            </div>
        {:else}
            <div class="p-3 text-sm text-muted-foreground">
                {$_("ai.chat.messages.deletedReference")}
            </div>
        {/if}
    </HoverCard.Content>
</HoverCard.Root>
