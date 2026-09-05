<script lang="ts">
    import type {Component} from "svelte";
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
    import {cn} from "$lib/utils";
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

<!--
  The icon sits on the first line rather than in the middle of the message: a reason that wraps
  would otherwise pull it half a line down, out of the row the eye reads it in. `mt-0.5` is what
  centres a 16px icon on a 20px line.
-->
{#snippet status(Icon: Component<{class?: string}>, message: string, tone: string)}
    <div class={cn("flex flex-row items-start gap-2 text-sm", tone)}>
        <Icon class="mt-0.5 size-4 shrink-0" />
        <span>{message}</span>
    </div>
{/snippet}

<Dialog.Root bind:open>
    <Dialog.Content>
        <Dialog.Header>
            <Dialog.Title>{$_("settings.emailAccounts.new.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.emailAccounts.new.description")}</Dialog.Description>
        </Dialog.Header>

        <form class="flex flex-col gap-2">
            <div class="flex flex-row gap-3">
                <Field.Field>
                    <Field.Label for={"imap-host-" + id}>
                        {$_("settings.emailAccounts.new.host")}
                    </Field.Label>
                    <Input
                            id={"imap-host-" + id}
                            type="text"
                            placeholder="imap.example.com"
                            bind:value={
                                () => viewModel.host,
                                (host: string) => viewModel.setHost(host)
                            }
                    />
                </Field.Field>

                <Field.Field class="w-24">
                    <Field.Label for={"imap-port-" + id}>
                        {$_("settings.emailAccounts.new.port")}
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
            </div>

            <!--
              Under both fields, because the answer is about the pair, and the height of one line is
              held free so the dialog does not resize under the pointer on every verdict.
            -->
            <div aria-live="polite" class="min-h-5">
                {#if test.type === "testing"}
                    {@render status(Spinner, $_("settings.emailAccounts.new.test.testing"), "text-muted-foreground")}
                {:else if loginDisabled}
                    {@render status(WarningIcon, $_("settings.emailAccounts.new.test.loginDisabled"), "")}
                {:else if test.type === "reachable"}
                    {@render status(CheckCircleIcon, $_("settings.emailAccounts.new.test.reachable"), "")}
                {:else if test.type === "unreachable"}
                    {@render status(WarningCircleIcon, unreachableReason ?? "", "text-destructive")}
                {:else if test.type === "failed"}
                    {@render status(WarningIcon, $_("settings.emailAccounts.new.test.failed"), "text-muted-foreground")}
                {/if}
            </div>
        </form>

        <Dialog.Footer>
            <Button
                    variant="secondary"
                    onclick={() => open = false}
            >{$_("settings.emailAccounts.new.cancel")}</Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
