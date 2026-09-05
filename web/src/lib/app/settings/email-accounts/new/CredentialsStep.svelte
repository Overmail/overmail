<script lang="ts">
    import * as Field from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import SetupStatusLine from "./SetupStatusLine.svelte";
    import type {NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte.ts";
    import {CheckCircleIcon, WarningCircleIcon, WarningIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {viewModel}: {viewModel: NewEmailAccountViewModel} = $props();

    const id = $props.id();
    const test = $derived(viewModel.imapLoginTest);

    const KNOWN = ["invalid_credentials", "connection_failed", "timeout"];
    const reason = $derived(
        test.type !== "rejected"
            ? ""
            : $_(`settings.emailAccounts.new.credentials.test.outcome.${KNOWN.includes(test.outcome) ? test.outcome : "unknown"}`),
    );
</script>

<div class="flex flex-col gap-2">
    <div class="flex flex-col gap-3">
        <Field.Field>
            <Field.Label for={"imap-username-" + id}>
                {$_("settings.emailAccounts.new.credentials.username")}
            </Field.Label>
            <Input
                    id={"imap-username-" + id}
                    type="text"
                    autocomplete="username"
                    placeholder="julius@example.com"
                    bind:value={() => viewModel.username, (username: string) => viewModel.setUsername(username)}
            />
        </Field.Field>

        <Field.Field>
            <Field.Label for={"imap-password-" + id}>
                {$_("settings.emailAccounts.new.credentials.password")}
            </Field.Label>
            <Input
                    id={"imap-password-" + id}
                    type="password"
                    autocomplete="current-password"
                    bind:value={() => viewModel.password, (password: string) => viewModel.setPassword(password)}
            />
        </Field.Field>
    </div>

    <div aria-live="polite" class="min-h-5">
        {#if test.type === "testing"}
            <SetupStatusLine
                    icon={Spinner}
                    message={$_("settings.emailAccounts.new.credentials.test.testing")}
                    tone="text-muted-foreground"
            />
        {:else if test.type === "authenticated"}
            <SetupStatusLine
                    icon={CheckCircleIcon}
                    message={$_("settings.emailAccounts.new.credentials.test.authenticated")}
            />
        {:else if test.type === "rejected"}
            <SetupStatusLine icon={WarningCircleIcon} message={reason} tone="text-destructive" />
        {:else if test.type === "failed"}
            <SetupStatusLine
                    icon={WarningIcon}
                    message={$_("settings.emailAccounts.new.credentials.test.failed")}
                    tone="text-muted-foreground"
            />
        {/if}
    </div>
</div>
