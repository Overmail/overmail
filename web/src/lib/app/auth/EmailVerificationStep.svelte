<script lang="ts" module>
    import EmailVerificationStep from "./EmailVerificationStep.svelte";
</script>

<script lang="ts">
    import {useAuthentiktContext} from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {EmailVerificationPlugin} from "./EmailVerificationPlugin.svelte";

    let {onRestart}: { onRestart: () => void } = $props();

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<EmailVerificationPlugin>(
        "overmail/email-verification",
        EmailVerificationStep,
        (a, ns) => new EmailVerificationPlugin(a, ns),
    );
</script>

{#if plugin.isActive}
    <form onsubmit={(e) => { e.preventDefault(); plugin.submit(); }}>
        <p>{$_("auth.signin.code.sent", {values: {email: plugin.maskedEmail}})}</p>
        <label>
            {$_("auth.signin.code.label")}
            <input bind:value={plugin.code} inputmode="numeric" autocomplete="one-time-code" />
        </label>

        {#if plugin.status === "invalid_code"}
            <p role="alert">{$_("auth.signin.code.error")}</p>
        {:else if plugin.status === "error"}
            <p role="alert">{$_("auth.signin.error")}</p>
        {/if}

        <button type="submit" disabled={plugin.status === "loading" || !plugin.code}>
            {$_("auth.signin.code.submit")}
        </button>
        <button type="button" onclick={onRestart} disabled={plugin.status === "loading"}>
            {$_("auth.signin.code.restart")}
        </button>
    </form>
{/if}
