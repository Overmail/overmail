<script lang="ts" module>
    import EmailStep from "./EmailStep.svelte";
</script>

<script lang="ts">
    import {
        EmailUserSelectionPlugin,
        useAuthentiktContext,
        type EmailUserSelectionPluginInstance,
    } from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {UserIcon} from "phosphor-svelte";
    import AuthShell from "./AuthShell.svelte";
    import AuthField from "./AuthField.svelte";
    import AuthSubmit from "./AuthSubmit.svelte";
    import AuthError from "./AuthError.svelte";

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<EmailUserSelectionPluginInstance>(
        "authentikt-builtin/email",
        EmailStep,
        (a, ns) => new EmailUserSelectionPlugin(a, ns),
    );

    const loading = $derived(plugin.status === "loading");
    const invalid = $derived(plugin.status === "user_not_existing" || plugin.status === "error");

    let input = $state<HTMLInputElement>();

    async function submit() {
        await plugin.submit();
        // Sent with the button, the focus is on it; the answer is to correct the field.
        if (invalid) input?.focus();
    }
</script>

{#if plugin.isActive}
    <AuthShell
            title={$_("auth.signin.identifier.headline")}
            description={$_("auth.signin.identifier.description")}
            onsubmit={() => void submit()}
    >
        <div class="flex gap-2">
            <!-- The server accepts the username as well (withUsername), the field is still `email`.
                 The one thing on the page to type into, so the cursor starts there. -->
            <AuthField
                    bind:ref={input}
                    bind:value={plugin.email}
                    icon={UserIcon}
                    {invalid}
                    oninput={() => { if (invalid) plugin.status = "ready"; }}
                    placeholder={$_("auth.signin.identifier.label")}
                    aria-label={$_("auth.signin.identifier.label")}
                    autocomplete="username"
                    autofocus
            />
            <AuthSubmit {loading} disabled={!plugin.email.trim()} label={$_("auth.signin.identifier.submit")} />
        </div>

        {#if invalid}
            <AuthError inset message={plugin.status === "user_not_existing" ? $_("auth.signin.identifier.error") : null} />
        {/if}
    </AuthShell>
{/if}
