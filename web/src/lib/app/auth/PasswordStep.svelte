<script lang="ts" module>
    import PasswordStep from "./PasswordStep.svelte";
</script>

<script lang="ts">
    import {
        PasswordPlugin,
        useAuthentiktContext,
        type PasswordPluginInstance,
    } from "@julius-babies/authentikt-svelte";
    import {_} from "svelte-i18n";
    import {ArrowLeftIcon, EyeIcon, EyeSlashIcon, LockSimpleIcon} from "phosphor-svelte";
    import AuthShell from "./AuthShell.svelte";
    import AuthField from "./AuthField.svelte";
    import AuthSubmit from "./AuthSubmit.svelte";
    import AuthError from "./AuthError.svelte";
    import AuthLink from "./AuthLink.svelte";

    let {onRestart}: { onRestart: () => void } = $props();

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<PasswordPluginInstance>(
        "authentikt-builtin/password",
        PasswordStep,
        (a, ns) => new PasswordPlugin(a, ns),
    );

    const loading = $derived(plugin.status === "loading");
    // Locked once the tries are used up; the countdown and the reload afterwards come with the plugin.
    const locked = $derived(plugin.rateLimit?.isLocked ?? false);
    const invalid = $derived(locked || plugin.status === "password_incorrect" || plugin.status === "error");

    // Set by the email step. authentikt's check does not send the user yet, so after a reload in the
    // middle of the flow it is gone and the greeting goes without the name.
    const name = $derived(auth.currentFlow?.user?.displayName);

    let input = $state<HTMLInputElement>();
    let revealed = $state(false);

    async function submit() {
        await plugin.submit();
        // Selected, so the next try replaces the wrong one instead of being appended to it.
        if (invalid) input?.select();
    }
</script>

{#if plugin.isActive}
    <AuthShell
            title={name ? $_("auth.signin.greetingNamed", {values: {name}}) : $_("auth.signin.greeting")}
            description={$_("auth.signin.password.description")}
            onsubmit={() => void submit()}
    >
        <!-- For the password manager: which account this password belongs to. -->
        <input type="hidden" autocomplete="username" value={auth.currentFlow?.user?.username ?? ""} />

        <div class="flex gap-2">
            <!-- The one thing on the page to type into, so the cursor starts there. -->
            <AuthField
                    bind:ref={input}
                    bind:value={plugin.password}
                    icon={LockSimpleIcon}
                    {invalid}
                    oninput={() => { if (invalid) plugin.status = "ready"; }}
                    type={revealed ? "text" : "password"}
                    placeholder={$_("auth.signin.password.label")}
                    aria-label={$_("auth.signin.password.label")}
                    disabled={locked}
                    autocomplete="current-password"
                    autofocus
            >
                {#snippet trailing()}
                    <!-- Inside the field, as it is about the field; the focus stays in the input. -->
                    <button
                            type="button"
                            class="flex size-9 shrink-0 cursor-pointer items-center justify-center rounded-lg text-muted-foreground transition-colors hover:text-foreground"
                            onmousedown={(e) => e.preventDefault()}
                            onclick={() => revealed = !revealed}
                            aria-label={revealed ? $_("auth.signin.password.hide") : $_("auth.signin.password.show")}
                            aria-pressed={revealed}
                    >
                        {#if revealed}
                            <EyeSlashIcon class="size-4" />
                        {:else}
                            <EyeIcon class="size-4" />
                        {/if}
                    </button>
                {/snippet}
            </AuthField>
            <AuthSubmit {loading} disabled={locked || !plugin.password} label={$_("auth.signin.password.submit")} />
        </div>

        {#if invalid}
            <AuthError
                    inset
                    rateLimit={plugin.rateLimit}
                    message={plugin.status === "password_incorrect" ? $_("auth.signin.password.error") : null}
            />
        {/if}

        {#snippet actions()}
            <AuthLink icon={ArrowLeftIcon} onclick={onRestart} disabled={loading}>
                {$_("auth.signin.restart")}
            </AuthLink>
        {/snippet}
    </AuthShell>
{/if}
