<script lang="ts" module>
    import TotpStep from "./TotpStep.svelte";
</script>

<script lang="ts">
    import {
        TotpPlugin,
        useAuthentiktContext,
        type TotpPluginInstance,
    } from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {ArrowLeftIcon, EnvelopeSimpleIcon} from "phosphor-svelte";
    import AuthShell from "./AuthShell.svelte";
    import AuthCodeField from "./AuthCodeField.svelte";
    import AuthSubmit from "./AuthSubmit.svelte";
    import AuthError from "./AuthError.svelte";
    import AuthLink from "./AuthLink.svelte";

    let {onRestart}: { onRestart: () => void } = $props();

    /** What authenticator apps show by default, and what the server checks. */
    const CODE_LENGTH = 6;

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

    let field = $state<AuthCodeField>();

    async function submit() {
        if (loading || locked || plugin.totp.length < CODE_LENGTH) return;
        await plugin.submit();
        // All six boxes are full, so a wrong code would have to be deleted digit by digit first.
        if (plugin.status === "totp_incorrect" || plugin.status === "rate_limited") plugin.totp = "";
        if (invalid) await field?.reject();
    }
</script>

{#if plugin.isActive}
    <AuthShell
            title={name ? $_("auth.signin.greetingNamed", {values: {name}}) : $_("auth.signin.greeting")}
            description={$_("auth.signin.totp.description")}
            onsubmit={() => void submit()}
    >
        <div class="flex gap-2">
            <AuthCodeField
                    bind:this={field}
                    bind:value={plugin.totp}
                    id="signin-totp"
                    length={CODE_LENGTH}
                    disabled={loading || locked}
                    label={$_("auth.signin.totp.label")}
                    oninput={() => { if (invalid && plugin.totp) plugin.status = "ready"; }}
                    oncomplete={() => void submit()}
            />
            <AuthSubmit {loading} disabled={locked || plugin.totp.length < CODE_LENGTH} label={$_("auth.signin.totp.submit")} />
        </div>

        {#if invalid}
            <AuthError
                    rateLimit={plugin.rateLimit}
                    message={plugin.status === "totp_incorrect" ? $_("auth.signin.totp.error") : null}
            />
        {/if}

        {#snippet actions()}
            {#if canUseEmailCode}
                <!-- Sends the mail only now: the code step mails it as it is entered. -->
                <AuthLink icon={EnvelopeSimpleIcon} onclick={() => void auth.switchToAlternative(EMAIL_CODE)} disabled={loading}>
                    {$_("auth.signin.totp.useEmail")}
                </AuthLink>
            {/if}
            <AuthLink icon={ArrowLeftIcon} onclick={onRestart} disabled={loading}>
                {$_("auth.signin.restart")}
            </AuthLink>
        {/snippet}
    </AuthShell>
{/if}
