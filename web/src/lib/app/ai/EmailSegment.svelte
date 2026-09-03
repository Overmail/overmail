<script lang="ts">
    import {EnvelopeSimpleIcon} from "phosphor-svelte";
    import type {PromptEmail} from "./prompt";
    import {shortSubject} from "./emailSubject";

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
    {#if email.avatarUrl}
        <img src={email.avatarUrl} alt="" class="inline size-3.5 rounded-full object-cover align-[-0.2em]"/>
    {:else}
        <EnvelopeSimpleIcon class="inline size-3.5 align-[-0.2em]"/>
    {/if}
    {shortSubject(email.subject)}
</span>
