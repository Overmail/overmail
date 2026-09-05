<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {
        DEFAULT_IMAP_PORT,
        NewEmailAccountViewModel,
    } from "$lib/app/settings/email-accounts/new/NewEmailAccountViewModel.svelte.ts";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {useRepositories} from "$lib/repository/repositories";
    import {CheckCircleIcon, WarningCircleIcon, WarningIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {
        open = $bindable(false),
    }: {
        open: boolean,
    } = $props();

    const id = $props.id();

    const {inboxSetup} = useRepositories();
    const viewModel = new NewEmailAccountViewModel(inboxSetup);

    // The dialog outlives one opening of it, so a test scheduled for a host the user walked away
    // from would still run -- and still connect to somebody's server.
    $effect(() => {
        if (!open) viewModel.dispose();
    });

    const test = $derived(viewModel.imapServerTest);

    /**
     * A server that answered but advertises LOGINDISABLED takes no password over this connection,
     * which the next two fields of this form are about. Reachable, but not usable, so it is worth
     * saying now rather than after the credentials were typed.
     */
    const loginDisabled = $derived(
        test.type === "reachable" && test.capabilities.includes("LOGINDISABLED"),
    );

    /** Every outcome the catalog has wording for; anything newer falls back to `unknown`. */
    const KNOWN_OUTCOMES = ["host_not_found", "connection_failed", "tls_failed", "no_imap_server", "timeout"];
    const unreachableReason = $derived(
        test.type !== "unreachable"
            ? null
            : $_(`settings.emailAccounts.new.test.outcome.${KNOWN_OUTCOMES.includes(test.outcome) ? test.outcome : "unknown"}`),
    );
</script>

<Dialog.Root bind:open>
    <Dialog.Content>
        <Dialog.Header>
            <Dialog.Title>{$_("settings.emailAccounts.new.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.emailAccounts.new.description")}</Dialog.Description>
        </Dialog.Header>

        <div class="flex flex-col flex-1">
            <form>
                <Field.Group>
                    <Field.Set>
                        <Field.Legend>{$_("settings.emailAccounts.new.provider.legend")}</Field.Legend>
                        <Field.Description>
                            {$_("settings.emailAccounts.new.provider.description")}<br />
                            {$_("settings.emailAccounts.new.provider.tlsHint")}
                        </Field.Description>

                        <Field.Group class="flex flex-row gap-4">
                            <Field.Field>
                                <Field.Label for={"imap-host-" + id}>
                                    {$_("settings.emailAccounts.new.provider.host")}
                                </Field.Label>
                                <Input
                                        id={"imap-host-" + id}
                                        type="text"
                                        placeholder="imap.example.com"
                                        aria-invalid={test.type === "unreachable"}
                                        bind:value={
                                            () => viewModel.host,
                                            (host: string) => viewModel.setHost(host)
                                        }
                                />
                            </Field.Field>

                            <Field.Field class="w-24">
                                <Field.Label for={"imap-port-" + id}>
                                    {$_("settings.emailAccounts.new.provider.port")}
                                </Field.Label>
                                <Input
                                        id={"imap-port-" + id}
                                        type="number"
                                        placeholder={String(DEFAULT_IMAP_PORT)}
                                        bind:value={
                                            () => viewModel.port,
                                            (port: number) => viewModel.setPort(port)
                                        }
                                />
                            </Field.Field>
                        </Field.Group>
                    </Field.Set>

                    <!-- Below both fields, not on one of them: the answer is about the pair. -->
                    <div aria-live="polite">
                        {#if test.type === "testing"}
                            <div class="flex flex-row items-center gap-2">
                                <Spinner class="h-4 w-4" />
                                <span class="text-muted-foreground text-sm">
                                    {$_("settings.emailAccounts.new.test.testing")}
                                </span>
                            </div>
                        {:else if test.type === "reachable" && loginDisabled}
                            <div class="flex flex-row items-center gap-2">
                                <WarningIcon class="h-4 w-4 shrink-0" />
                                <span class="text-sm">{$_("settings.emailAccounts.new.test.loginDisabled")}</span>
                            </div>
                        {:else if test.type === "reachable"}
                            <div class="flex flex-row items-center gap-2">
                                <CheckCircleIcon class="h-4 w-4 shrink-0" weight="fill" />
                                <span class="text-sm">{$_("settings.emailAccounts.new.test.reachable")}</span>
                            </div>
                        {:else if test.type === "unreachable"}
                            <div class="flex flex-row items-center gap-2 text-destructive">
                                <WarningCircleIcon class="h-4 w-4 shrink-0" weight="fill" />
                                <span class="text-sm">{unreachableReason}</span>
                            </div>
                        {:else if test.type === "failed"}
                            <div class="flex flex-row items-center gap-2">
                                <WarningIcon class="h-4 w-4 shrink-0" />
                                <span class="text-muted-foreground text-sm">
                                    {$_("settings.emailAccounts.new.test.failed")}
                                </span>
                            </div>
                        {/if}
                    </div>
                </Field.Group>
            </form>
        </div>

        <Dialog.Footer>
            <Button
                    variant="secondary"
                    onclick={() => open = false}
            >{$_("settings.emailAccounts.new.cancel")}</Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
