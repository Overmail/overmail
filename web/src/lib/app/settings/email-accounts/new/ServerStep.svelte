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
     * The field a step opens on.
     *
     * Set here rather than left to the dialog: its own focus management only runs when the dialog
     * opens, and lands on the first field by accident of tab order. On a step *change* nothing
     * runs at all -- the field that had focus is unmounted with the step it belonged to, so focus
     * falls back to the body and the keyboard flow stops. This effect runs on mount, which is
     * exactly "this step became the one showing".
     */
    let firstField: HTMLInputElement | null = $state(null);
    $effect(() => {
        const field = firstField;
        if (!field) return;
        // After a frame, not right away: the field that had focus is unmounted with the step it
        // belonged to, and the dialog's focus trap notices and pulls focus to the first tabbable
        // thing it finds -- a breadcrumb button. That recovery runs after this effect, so
        // focusing synchronously here is undone a moment later.
        const frame = requestAnimationFrame(() => field.focus());
        return () => cancelAnimationFrame(frame);
    });


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

<!-- A form, so Enter in any field means "go on" -- the same thing the footer button does. -->
<form
        class="flex flex-col gap-2"
        onsubmit={(event) => {
            event.preventDefault();
            viewModel.submit();
        }}
>
    <div class="flex flex-row gap-3">
        <Field.Field>
            <Field.Label for={"imap-host-" + id}>{$_("settings.emailAccounts.new.server.host")}</Field.Label>
            <Input
                    bind:ref={firstField}
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

    <!--
      What makes Enter submit at all: a form only does so implicitly when it owns a submit button,
      and the visible one lives in the dialog footer, disabled for exactly as long as the step has
      not checked out -- which is when Enter still has something to do, see `submit`.
    -->
    <button type="submit" class="sr-only" tabindex="-1" aria-hidden="true"></button>
</form>
