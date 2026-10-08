<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte.ts";
    import SetupProgress from "./SetupProgress.svelte";
    import ServerStep from "./ServerStep.svelte";
    import CredentialsStep from "./CredentialsStep.svelte";
    import FolderStep from "./FolderStep.svelte";
    import AnimatedHeight from "./AnimatedHeight.svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";
    import {untrack} from "svelte";
    import {CheckCircleIcon} from "phosphor-svelte";
    import {OAUTH_PROVIDER_LOOKS} from "./oauthProviderLooks";

    let {
        open = $bindable(false),
        onCreated,
        oauthOnboardingId = null,
    }: {
        open: boolean,
        /** Called once an inbox exists, so whoever lists them can re-read. */
        onCreated?: () => void,
        /** A sign-in at a provider to continue at the folders, as the oauth callback named it. */
        oauthOnboardingId?: string | null,
    } = $props();

    const {inboxSetup} = useRepositories();
    const viewModel = new NewEmailAccountViewModel(inboxSetup);

    // Asked now, while the dialog is still closed, so the server step can open with its sign-in
    // buttons in place. A failure is left to the step, which asks again.
    inboxSetup.oauthProviders().catch(() => {});

    // The dialog outlives one opening of it, so a check scheduled for a host the user walked away
    // from would still run -- and still connect to somebody's server. Every opening starts on an
    // empty form, except one that continues a sign-in: that one resets itself, see below.
    $effect(() => {
        if (!open) {
            viewModel.dispose();
            return;
        }
        untrack(() => {
            if (!oauthOnboardingId) viewModel.reset();
        });
    });

    // Untracked: continuing resets the form, and everything that reads would otherwise become a
    // reason to continue -- and reset -- all over again.
    $effect(() => {
        const id = oauthOnboardingId;
        if (!id) return;
        untrack(async () => {
            await viewModel.continueOAuthOnboarding(id);
            // An inbox that was waiting for this sign-in imports again; the list says so.
            if (viewModel.reauthenticated) onCreated?.();
        });
    });

    const step = $derived(viewModel.step);

    /**
     * Creates the inbox and closes on success.
     *
     * Reset before closing, not after: the dialog is kept mounted, so the next inbox would
     * otherwise open inside the one that was just created. A failure leaves everything where it
     * is, with the reason under the table.
     */
    async function finish() {
        if (!(await viewModel.submitInbox())) return;
        viewModel.reset();
        open = false;
        onCreated?.();
    }

    /** Whether the step showing can be left, which is what the "next" button waits for. */
    const canGoOn = $derived(
        step === "server"
            ? viewModel.canLeaveServerStep
            : step === "credentials"
              ? viewModel.canLeaveCredentialsStep
              : false,
    );
</script>

<Dialog.Root bind:open>
    <!--
      One width for every step, set by the widest of them: six columns of folder table need the
      room, and a dialog that resizes sideways as the user steps through it is worse than two
      steps with air around their fields. The fields themselves stay narrow, see below.
    -->
    <Dialog.Content class="sm:max-w-4xl">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.emailAccounts.new.title")}</Dialog.Title>
            <Dialog.Description>
                {#if viewModel.reauthenticated}
                    {$_("settings.emailAccounts.new.reauthenticated.description")}
                {:else}
                    {$_(`settings.emailAccounts.new.${step}.description`, {values: {host: viewModel.host}})}
                {/if}
            </Dialog.Description>
        </Dialog.Header>

        {#if viewModel.reauthenticated}
            <!-- Nothing to set up: the inbox exists and has its sign-in back. -->
            {@const looks = viewModel.signedInWith ? OAUTH_PROVIDER_LOOKS[viewModel.signedInWith] : undefined}
            <div class="flex flex-row items-start gap-2 text-sm">
                <CheckCircleIcon class="mt-0.5 size-4 shrink-0 text-green-600" />
                <span>
                    {$_("settings.emailAccounts.new.reauthenticated.message", {
                        values: {provider: looks?.name ?? viewModel.signedInWith ?? "", address: viewModel.username},
                    })}
                </span>
            </div>

            <Dialog.Footer>
                <Button onclick={() => (open = false)}>{$_("settings.emailAccounts.new.close")}</Button>
            </Dialog.Footer>
        {:else}
            <SetupProgress
                    current={step}
                    canEnter={(target) => viewModel.canEnter(target)}
                    onNavigate={(target) => viewModel.goTo(target)}
            />

            <!--
              Height is the one dimension that may change, and it changes a lot: two fields, then two
              more, then a table that grows as folders arrive. Animated so the footer travels rather
              than jumping out from under the pointer.
            -->
            <AnimatedHeight>
                {#if step === "server"}
                    <ServerStep {viewModel} />
                {:else if step === "credentials"}
                    <CredentialsStep {viewModel} />
                {:else}
                    <FolderStep {viewModel} />
                {/if}
            </AnimatedHeight>

            <Dialog.Footer>
                <Button variant="secondary" onclick={() => (open = false)}>
                    {$_("settings.emailAccounts.new.cancel")}
                </Button>
                {#if step !== "folders"}
                    <!-- Enabled by the check of the step it sits under, not by the fields being filled. -->
                    <Button disabled={!canGoOn} onclick={() => viewModel.goToNextStep()}>
                        {$_("settings.emailAccounts.new.next")}
                    </Button>
                {:else}
                    <Button disabled={!viewModel.canSubmit} onclick={finish}>
                        {$_("settings.emailAccounts.new.finish")}
                    </Button>
                {/if}
            </Dialog.Footer>
        {/if}
    </Dialog.Content>
</Dialog.Root>
