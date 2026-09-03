<script lang="ts">
    import {EnvelopeSimpleIcon} from "phosphor-svelte";
    import {OvermailAvatar} from "$lib/components/avatar";
    import type {PromptEmail} from "$lib/app/ai/composer/prompt";
    import {shortSubject} from "$lib/app/ai/emailSubject";

    let {
        email,
    }: {
        email: PromptEmail;
    } = $props();
</script>

<span
        class="inline whitespace-nowrap bg-card text-card-foreground px-1 mx-0.5 rounded outline"
        title={email.subject}
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
