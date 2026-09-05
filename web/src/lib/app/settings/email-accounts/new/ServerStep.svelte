<script lang="ts">
    import * as Field from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import SetupStatusLine from "./SetupStatusLine.svelte";
    import {DEFAULT_IMAP_PORT, type NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte.ts";
    import {CheckCircleIcon, WarningCircleIcon, WarningIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {viewModel}: {viewModel: NewEmailAccountViewModel} = $props();

    const id = $props.id();
    const test = $derived(viewModel.imapServerTest);

    /**
     * A server that answered but advertises LOGINDISABLED takes no password over this connection,
     * which the next step is about. Reachable, but not usable, so it is worth saying now.
     */
    const loginDisabled = $derived(
        test.type === "reachable" && test.capabilities.includes("LOGINDISABLED"),
    );

    /** Every outcome the catalog has wording for; anything newer falls back to `unknown`. */
    const KNOWN = ["host_not_found", "connection_failed", "tls_failed", "no_imap_server", "timeout"];
    const reason = $derived(
        test.type !== "unreachable"
            ? ""
            : $_(`settings.emailAccounts.new.server.test.outcome.${KNOWN.includes(test.outcome) ? test.outcome : "unknown"}`),
    );
</script>

<div class="flex flex-col gap-2">
    <div class="flex flex-row gap-3">
        <Field.Field>
            <Field.Label for={"imap-host-" + id}>{$_("settings.emailAccounts.new.server.host")}</Field.Label>
            <Input
                    id={"imap-host-" + id}
                    type="text"
                    placeholder="imap.example.com"
                    bind:value={() => viewModel.host, (host: string) => viewModel.setHost(host)}
            />
        </Field.Field>

        <Field.Field class="w-24">
            <Field.Label for={"imap-port-" + id}>{$_("settings.emailAccounts.new.server.port")}</Field.Label>
            <Input
                    id={"imap-port-" + id}
                    type="number"
                    placeholder={String(DEFAULT_IMAP_PORT)}
                    bind:value={() => viewModel.port, (port: number) => viewModel.setPort(port)}
            />
        </Field.Field>
    </div>

    <!--
      Under both fields, because the answer is about the pair, and the height of one line is held
      free so the dialog does not resize on every verdict.
    -->
    <div aria-live="polite" class="min-h-5">
        {#if test.type === "testing"}
            <SetupStatusLine
                    icon={Spinner}
                    message={$_("settings.emailAccounts.new.server.test.testing")}
                    tone="text-muted-foreground"
            />
        {:else if loginDisabled}
            <SetupStatusLine icon={WarningIcon} message={$_("settings.emailAccounts.new.server.test.loginDisabled")} />
        {:else if test.type === "reachable"}
            <SetupStatusLine icon={CheckCircleIcon} message={$_("settings.emailAccounts.new.server.test.reachable")} />
        {:else if test.type === "unreachable"}
            <SetupStatusLine icon={WarningCircleIcon} message={reason} tone="text-destructive" />
        {:else if test.type === "failed"}
            <SetupStatusLine
                    icon={WarningIcon}
                    message={$_("settings.emailAccounts.new.server.test.failed")}
                    tone="text-muted-foreground"
            />
        {/if}
    </div>
</div>
