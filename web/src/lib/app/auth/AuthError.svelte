<script lang="ts">
    import {formatLockDuration, type RateLimitState} from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {slide} from "svelte/transition";
    import {WarningCircleIcon} from "phosphor-svelte";

    let {rateLimit = null, message = null, inset = false}: {
        rateLimit?: RateLimitState | null,
        /** What was wrong with the input; null for anything else, which gets the generic message. */
        message?: string | null,
        /** Lines the text up with the text of a field that has an icon in front of it. */
        inset?: boolean,
    } = $props();
</script>

<p role="alert" class={["flex items-center gap-2 text-sm text-destructive", inset && "ps-3.5"]} transition:slide={{duration: 150}}>
    <WarningCircleIcon class="size-4 shrink-0" />
    {#if rateLimit?.isLocked}
        {$_("auth.signin.rateLimited", {values: {time: formatLockDuration(rateLimit.remainingLockSeconds)}})}
    {:else if message}
        {message}
        {#if rateLimit}{$_("auth.signin.triesLeft", {values: {count: rateLimit.remainingTries}})}{/if}
    {:else}
        {$_("auth.signin.error")}
    {/if}
</p>
