<script lang="ts" module>
    import TotpStep from "./TotpStep.svelte";
</script>

<script lang="ts">
    import {tick} from "svelte";
    import {
        formatLockDuration,
        TotpPlugin,
        useAuthentiktContext,
        type TotpPluginInstance,
    } from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {slide} from "svelte/transition";
    import {ArrowLeftIcon, ArrowRightIcon, EnvelopeSimpleIcon, WarningCircleIcon} from "phosphor-svelte";
    import * as InputOTP from "$lib/components/ui/input-otp";
    import {Spinner} from "$lib/components/ui/spinner";
    import AuthStep from "./AuthStep.svelte";

    let {onRestart}: { onRestart: () => void } = $props();

    /** What authenticator apps show by default, and what the server checks. */
    const CODE_LENGTH = 6;

    /** The pin input's hidden <input>, the one that takes the keyboard. */
    const INPUT_ID = "signin-totp";

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<TotpPluginInstance>(
        "authentikt-builtin/totp",
        TotpStep,
        (a, ns) => new TotpPlugin(a, ns),
    );

    /** The mailed code, where the account allows it in place of the app. */
    const EMAIL_CODE = "overmail/email-verification";
    const canUseEmailCode = $derived(auth.alternatives.includes(EMAIL_CODE));

    const loading = $derived(plugin.status === "loading");
    // Locked once the tries are used up; the countdown and the reload afterwards come with the plugin.
    const locked = $derived(plugin.rateLimit?.isLocked ?? false);
    const invalid = $derived(locked || plugin.status === "totp_incorrect" || plugin.status === "error");

    // Set by the email step. authentikt's check does not send the user yet, so after a reload in the
    // middle of the flow it is gone and the greeting goes without the name.
    const name = $derived(auth.currentFlow?.user?.displayName);

    async function submit() {
        if (loading || locked || plugin.totp.length < CODE_LENGTH) return;
        await plugin.submit();
        // All six boxes are full, so a wrong code would have to be deleted digit by digit first.
        if (plugin.status === "totp_incorrect" || plugin.status === "rate_limited") plugin.totp = "";
        // The input was disabled while the code was checked, which took the focus away from it.
        if (invalid) {
            await tick();
            document.getElementById(INPUT_ID)?.focus();
        }
    }
</script>

{#if plugin.isActive}
    <AuthStep>
        <div class="flex w-full max-w-md flex-col gap-8">
            <div class="flex flex-col gap-3">
                <h1 class="font-display text-3xl leading-tight text-balance sm:text-4xl lg:text-5xl">
                    {name
                        ? $_("auth.signin.greetingNamed", {values: {name}})
                        : $_("auth.signin.greeting")}
                </h1>
                <p class="text-muted-foreground">
                    {$_("auth.signin.totp.description")}
                </p>
            </div>

            <form class="flex flex-col gap-2" onsubmit={(e) => { e.preventDefault(); void submit(); }}>
                <div class="flex gap-2">
                    <!-- The one thing on the page to type into, so the cursor starts there. -->
                    <!-- svelte-ignore a11y_autofocus -->
                    <InputOTP.Root
                            bind:value={plugin.totp}
                            inputId={INPUT_ID}
                            maxlength={CODE_LENGTH}
                            pattern={"^\\d+$"}
                            disabled={loading || locked}
                            onValueChange={() => { if (invalid && plugin.totp) plugin.status = "ready"; }}
                            onComplete={() => void submit()}
                            aria-label={$_("auth.signin.totp.label")}
                            class="min-w-0 flex-1"
                            autofocus
                    >
                        {#snippet children({cells})}
                            <!-- The slots are apart, so each marks itself invalid; the group's ring around all of them
                                 would be drawn across the gaps. -->
                            <InputOTP.Group class="w-full gap-2 has-aria-invalid:ring-0">
                                {#each cells as cell (cell)}
                                    <!-- Filled boxes like the email field, apart instead of joined. -->
                                    <InputOTP.Slot
                                            {cell}
                                            aria-invalid={invalid}
                                            class="size-11 min-w-0 flex-1 rounded-xl border border-transparent bg-muted text-base font-medium first:rounded-xl last:rounded-xl data-[active=true]:ring-ring/20"
                                    />
                                {/each}
                            </InputOTP.Group>
                        {/snippet}
                    </InputOTP.Root>
                    <button
                            type="submit"
                            class="group/submit flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-xl bg-primary text-primary-foreground transition-all hover:bg-primary/85 active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
                            disabled={loading || locked || plugin.totp.length < CODE_LENGTH}
                            aria-label={$_("auth.signin.totp.submit")}
                    >
                        {#if loading}
                            <Spinner class="size-4" />
                        {:else}
                            <ArrowRightIcon class="size-4 transition-transform group-hover/submit:translate-x-0.5" weight="bold" />
                        {/if}
                    </button>
                </div>

                {#if invalid}
                    <p role="alert" class="flex items-center gap-2 ps-3.5 text-sm text-destructive" transition:slide={{duration: 150}}>
                        <WarningCircleIcon class="size-4 shrink-0" />
                        {#if locked}
                            {$_("auth.signin.rateLimited", {values: {time: formatLockDuration(plugin.rateLimit!.remainingLockSeconds)}})}
                        {:else if plugin.status === "totp_incorrect"}
                            {$_("auth.signin.totp.error")}
                            {#if plugin.rateLimit}{$_("auth.signin.triesLeft", {values: {count: plugin.rateLimit.remainingTries}})}{/if}
                        {:else}
                            {$_("auth.signin.error")}
                        {/if}
                    </p>
                {/if}
            </form>

            {#if canUseEmailCode}
                <!-- Sends the mail only now: the code step mails it as it is entered. -->
                <button
                        type="button"
                        class="flex w-fit cursor-pointer items-center gap-2 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
                        onclick={() => void auth.switchToAlternative(EMAIL_CODE)}
                        disabled={loading}
                >
                    <EnvelopeSimpleIcon class="size-4" />
                    {$_("auth.signin.totp.useEmail")}
                </button>
            {/if}

            <button
                    type="button"
                    class="flex w-fit cursor-pointer items-center gap-2 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
                    onclick={onRestart}
                    disabled={loading}
            >
                <ArrowLeftIcon class="size-4" />
                {$_("auth.signin.restart")}
            </button>
        </div>
    </AuthStep>
{/if}
