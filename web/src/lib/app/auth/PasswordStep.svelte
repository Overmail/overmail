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
    import {slide} from "svelte/transition";
    import {ArrowLeftIcon, ArrowRightIcon, EyeIcon, EyeSlashIcon, LockSimpleIcon, WarningCircleIcon} from "phosphor-svelte";
    import {Spinner} from "$lib/components/ui/spinner";
    import AuthStep from "./AuthStep.svelte";

    let {onRestart}: { onRestart: () => void } = $props();

    const auth = useAuthentiktContext();

    const plugin = auth.registerPlugin<PasswordPluginInstance>(
        "authentikt-builtin/password",
        PasswordStep,
        (a, ns) => new PasswordPlugin(a, ns),
    );

    const loading = $derived(plugin.status === "loading");
    const invalid = $derived(plugin.status === "password_incorrect" || plugin.status === "error");

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
    <AuthStep>
        <div class="flex w-full max-w-md flex-col gap-8">
            <div class="flex flex-col gap-3">
                <h1 class="font-display text-4xl leading-tight text-balance sm:text-5xl">
                    {name
                        ? $_("auth.signin.greetingNamed", {values: {name}})
                        : $_("auth.signin.greeting")}
                </h1>
                <p class="text-muted-foreground">{$_("auth.signin.password.description")}</p>
            </div>

            <form class="flex flex-col gap-2" onsubmit={(e) => { e.preventDefault(); void submit(); }}>
                <!-- For the password manager: which account this password belongs to. -->
                <input type="hidden" autocomplete="username" value={auth.currentFlow?.user?.username ?? ""} />

                <div class="flex gap-2">
                    <!-- The whole box is the field and takes the focus ring, the icon included. -->
                    <label
                            class={[
                                "group flex h-11 min-w-0 flex-1 items-center gap-2.5 rounded-xl border border-transparent bg-muted ps-3.5 pe-1",
                                "transition-[border-color,box-shadow,background-color] duration-200",
                                "focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/20",
                                invalid && "border-destructive focus-within:border-destructive focus-within:ring-destructive/15",
                            ]}
                    >
                        <LockSimpleIcon class="size-4 shrink-0 text-muted-foreground transition-colors group-focus-within:text-foreground" />
                        <!-- The one thing on the page to type into, so the cursor starts there. -->
                        <!-- svelte-ignore a11y_autofocus -->
                        <input
                                bind:this={input}
                                bind:value={plugin.password}
                                oninput={() => { if (invalid) plugin.status = "ready"; }}
                                type={revealed ? "text" : "password"}
                                class="h-full min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                                placeholder={$_("auth.signin.password.label")}
                                aria-label={$_("auth.signin.password.label")}
                                aria-invalid={invalid}
                                autocomplete="current-password"
                                autocapitalize="none"
                                spellcheck="false"
                                autofocus
                        />
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
                    </label>
                    <button
                            type="submit"
                            class="group/submit flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-xl bg-primary text-primary-foreground transition-all hover:bg-primary/85 active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
                            disabled={loading || !plugin.password}
                            aria-label={$_("auth.signin.password.submit")}
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
                        {plugin.status === "password_incorrect" ? $_("auth.signin.password.error") : $_("auth.signin.error")}
                    </p>
                {/if}
            </form>

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
