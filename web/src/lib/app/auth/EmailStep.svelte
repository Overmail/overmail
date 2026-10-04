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
    import {slide} from "svelte/transition";
    import {ArrowRightIcon, UserIcon, WarningCircleIcon} from "phosphor-svelte";
    import {Spinner} from "$lib/components/ui/spinner";
    import AuthStep from "./AuthStep.svelte";

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
    <AuthStep>
        <div class="flex w-full max-w-md flex-col gap-8">
            <div class="flex flex-col gap-3">
                <h1 class="font-display text-4xl leading-tight text-balance sm:text-5xl">
                    {$_("auth.signin.identifier.headline")}
                </h1>
                <p class="text-muted-foreground">{$_("auth.signin.identifier.description")}</p>
            </div>

            <!-- The server accepts the username as well (withUsername), the field is still `email`. -->
            <form class="flex flex-col gap-2" onsubmit={(e) => { e.preventDefault(); void submit(); }}>
                <div class="flex gap-2">
                    <!-- The whole box is the field and takes the focus ring, the icon included. -->
                    <label
                            class={[
                                "group flex h-11 min-w-0 flex-1 items-center gap-2.5 rounded-xl border border-transparent bg-muted px-3.5",
                                "transition-[border-color,box-shadow,background-color] duration-200",
                                "focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/20",
                                invalid && "border-destructive focus-within:border-destructive focus-within:ring-destructive/15",
                            ]}
                    >
                        <UserIcon class="size-4 shrink-0 text-muted-foreground transition-colors group-focus-within:text-foreground" />
                        <!-- The one thing on the page to type into, so the cursor starts there. -->
                        <!-- svelte-ignore a11y_autofocus -->
                        <input
                                bind:this={input}
                                bind:value={plugin.email}
                                oninput={() => { if (invalid) plugin.status = "ready"; }}
                                class="h-full min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                                placeholder={$_("auth.signin.identifier.label")}
                                aria-label={$_("auth.signin.identifier.label")}
                                aria-invalid={invalid}
                                autocomplete="username"
                                autocapitalize="none"
                                spellcheck="false"
                                autofocus
                        />
                    </label>
                    <button
                            type="submit"
                            class="group/submit flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-xl bg-primary text-primary-foreground transition-all hover:bg-primary/85 active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
                            disabled={loading || !plugin.email.trim()}
                            aria-label={$_("auth.signin.identifier.submit")}
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
                        {plugin.status === "user_not_existing" ? $_("auth.signin.identifier.error") : $_("auth.signin.error")}
                    </p>
                {/if}
            </form>
        </div>
    </AuthStep>
{/if}
