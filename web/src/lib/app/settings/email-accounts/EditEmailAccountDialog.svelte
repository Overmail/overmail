<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {WarningCircleIcon} from "phosphor-svelte";
    import ServerStep from "$lib/app/settings/email-accounts/new/ServerStep.svelte";
    import CredentialsStep from "$lib/app/settings/email-accounts/new/CredentialsStep.svelte";
    import FolderStep from "$lib/app/settings/email-accounts/new/FolderStep.svelte";
    import {NewEmailAccountViewModel} from "$lib/app/settings/email-accounts/new/NewEmailAccountViewModel.svelte.ts";
    import {useRepositories} from "$lib/repository/repositories";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {_} from "svelte-i18n";

    let {
        inbox = $bindable(null),
        onSaved,
    }: {
        /** The mailbox to edit; null closes the dialog. */
        inbox: Inbox | null,
        onSaved?: () => void,
    } = $props();

    const {inboxes: inboxRepository, inboxSetup} = useRepositories();

    /**
     * The same form the setup dialog drives, told it is editing.
     *
     * One model, so the checks, the debounces and the folder table are the same code in both
     * places -- what differs is where the checks are sent and what saving does, and both of those
     * are in here.
     */
    let viewModel: NewEmailAccountViewModel | null = $state(null);
    let loading: {type: "loading"} | {type: "ready"} | {type: "failed"} = $state({type: "loading"});

    /** The scan is a mailbox round trip, so it waits until the login is known to work. */
    let scanStarted = $state(false);

    $effect(() => {
        const target = inbox;
        if (!target) {
            viewModel = null;
            scanStarted = false;
            loading = {type: "loading"};
            return;
        }

        const form = new NewEmailAccountViewModel(inboxSetup, {
            inboxId: target.id,
            save: (imap, folders) => inboxRepository.update(target.id, imap, folders),
        });
        viewModel = form;
        loading = {type: "loading"};
        scanStarted = false;

        let cancelled = false;
        void (async () => {
            try {
                const detail = await inboxRepository.get(target.id);
                if (cancelled) return;
                form.prefill(detail);
                loading = {type: "ready"};
            } catch {
                if (!cancelled) loading = {type: "failed"};
            }
        })();

        return () => {
            cancelled = true;
            form.dispose();
        };
    });

    // Everything is on one screen here rather than behind "next", so the folder list has to start
    // itself. The login is what it needs, and nothing before that would tell it anything.
    $effect(() => {
        const form = viewModel;
        if (!form || scanStarted) return;
        if (form.imapLoginTest.type !== "authenticated") return;
        scanStarted = true;
        form.goTo("folders");
    });

    function close() {
        if (viewModel?.submitState.type === "saving") return;
        inbox = null;
    }

    async function save() {
        if (!viewModel) return;
        if (!(await viewModel.submitInbox())) return;
        inbox = null;
        onSaved?.();
    }
</script>

<Dialog.Root
        open={inbox !== null}
        onOpenChange={(open) => {
            if (!open) close();
        }}
>
    <!-- The same width as the setup dialog: it shows the same folder table. -->
    <Dialog.Content class="sm:max-w-4xl">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.emailAccounts.edit.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.emailAccounts.edit.description")}</Dialog.Description>
        </Dialog.Header>

        {#if loading.type === "loading"}
            <div class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
                <Spinner class="mt-0.5 size-4 shrink-0" />
                <span>{$_("settings.emailAccounts.list.loading")}</span>
            </div>
        {:else if loading.type === "failed"}
            <div class="text-destructive flex flex-row items-start gap-2 text-sm">
                <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                <span>{$_("settings.emailAccounts.edit.loadFailed")}</span>
            </div>
        {:else if viewModel}
            <!--
              All three at once rather than one after another: nothing here is being discovered for
              the first time, so making somebody walk through steps to reach the folders would be
              ceremony. Each section still checks itself as it is changed.
            -->
            <div class="flex flex-col gap-6">
                <section class="flex flex-col gap-2">
                    <h3 class="text-sm font-medium">{$_("settings.emailAccounts.edit.server")}</h3>
                    <div class="max-w-md">
                        <ServerStep viewModel={viewModel} />
                    </div>
                </section>

                <section class="flex flex-col gap-2">
                    <h3 class="text-sm font-medium">{$_("settings.emailAccounts.edit.credentials")}</h3>
                    <div class="max-w-md">
                        <CredentialsStep
                                viewModel={viewModel}
                                passwordPlaceholder={$_("settings.emailAccounts.edit.passwordPlaceholder")}
                        />
                    </div>
                </section>

                <section class="flex flex-col gap-2">
                    <h3 class="text-sm font-medium">{$_("settings.emailAccounts.edit.folders")}</h3>
                    <FolderStep viewModel={viewModel} />
                </section>
            </div>
        {/if}

        <Dialog.Footer>
            <Button
                    variant="secondary"
                    disabled={viewModel?.submitState.type === "saving"}
                    onclick={close}
            >
                {$_("settings.emailAccounts.new.cancel")}
            </Button>
            <Button disabled={!viewModel?.canSubmit} onclick={save}>
                {$_("settings.emailAccounts.edit.save")}
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
