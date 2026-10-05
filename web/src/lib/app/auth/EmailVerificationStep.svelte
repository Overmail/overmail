<script lang="ts" module>
    import EmailVerificationStep from "./EmailVerificationStep.svelte";
</script>

<script lang="ts">
    import {useAuthentiktContext} from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {ArrowLeftIcon, DeviceMobileIcon} from "phosphor-svelte";
    import AuthShell from "./AuthShell.svelte";
    import AuthCodeField from "./AuthCodeField.svelte";
    import AuthSubmit from "./AuthSubmit.svelte";
    import AuthError from "./AuthError.svelte";
    import AuthLink from "./AuthLink.svelte";
    import {EmailVerificationPlugin} from "./EmailVerificationPlugin.svelte";

    let {onRestart}: { onRestart: () => void } = $props();

    /** As long as the server's `CODE_LENGTH`. */
    const CODE_LENGTH = 6;

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<EmailVerificationPlugin>(
        "overmail/email-verification",
        EmailVerificationStep,
        (a, ns) => new EmailVerificationPlugin(a, ns),
    );

    /** The authenticator app, when this code was taken in its place. */
    const TOTP = "authentikt-builtin/totp";
    const canUseTotp = $derived(auth.alternatives.includes(TOTP));

    const loading = $derived(plugin.status === "loading");
    // Locked once the tries are used up; the countdown and the reload afterwards come with the plugin.
    const locked = $derived(plugin.rateLimit?.isLocked ?? false);
    const invalid = $derived(locked || plugin.status === "invalid_code" || plugin.status === "error");

    // Set by the email step. authentikt's check does not send the user yet, so after a reload in the
    // middle of the flow it is gone and the greeting goes without the name.
    const name = $derived(auth.currentFlow?.user?.displayName);

    let field = $state<AuthCodeField>();

    async function submit() {
        if (loading || locked || plugin.code.length < CODE_LENGTH) return;
        await plugin.submit();
        // All six boxes are full, so a wrong code would have to be deleted digit by digit first.
        if (plugin.status === "invalid_code" || plugin.status === "rate_limited") plugin.code = "";
        if (invalid) await field?.reject();
    }
</script>

{#if plugin.isActive}
    <AuthShell
            title={name ? $_("auth.signin.greetingNamed", {values: {name}}) : $_("auth.signin.greeting")}
            description={$_("auth.signin.code.sent", {values: {email: plugin.maskedEmail}})}
            onsubmit={() => void submit()}
    >
        <div class="flex gap-2">
            <AuthCodeField
                    bind:this={field}
                    bind:value={plugin.code}
                    id="signin-code"
                    length={CODE_LENGTH}
                    disabled={loading || locked}
                    label={$_("auth.signin.code.label")}
                    oninput={() => { if (invalid && plugin.code) plugin.status = "ready"; }}
                    oncomplete={() => void submit()}
            />
            <AuthSubmit {loading} disabled={locked || plugin.code.length < CODE_LENGTH} label={$_("auth.signin.code.submit")} />
        </div>

        {#if invalid}
            <AuthError
                    rateLimit={plugin.rateLimit}
                    message={plugin.status === "invalid_code" ? $_("auth.signin.code.error") : null}
            />
        {/if}

        {#snippet actions()}
            {#if canUseTotp}
                <AuthLink icon={DeviceMobileIcon} onclick={() => void auth.switchToAlternative(TOTP)} disabled={loading}>
                    {$_("auth.signin.code.useTotp")}
                </AuthLink>
            {/if}
            <AuthLink icon={ArrowLeftIcon} onclick={onRestart} disabled={loading}>
                {$_("auth.signin.restart")}
            </AuthLink>
        {/snippet}
    </AuthShell>
{/if}
