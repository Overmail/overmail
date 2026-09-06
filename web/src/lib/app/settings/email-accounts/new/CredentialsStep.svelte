<script lang="ts">
    import * as Field from "$lib/components/ui/field";
    import {Input} from "$lib/components/ui/input";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import SetupStatusLine from "./SetupStatusLine.svelte";
    import type {NewEmailAccountViewModel} from "./NewEmailAccountViewModel.svelte.ts";
    import {CheckCircleIcon, WarningCircleIcon, WarningIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";

    let {
        viewModel,
        passwordPlaceholder = "",
    }: {
        viewModel: NewEmailAccountViewModel,
        /** What an empty password field means. Only an edit screen has anything to say here. */
        passwordPlaceholder?: string,
    } = $props();

    const id = $props.id();
    const test = $derived(viewModel.imapLoginTest);

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


    const KNOWN = ["invalid_credentials", "connection_failed", "timeout"];
    const reason = $derived(
        test.type !== "rejected"
            ? ""
            : $_(`settings.emailAccounts.new.credentials.test.outcome.${KNOWN.includes(test.outcome) ? test.outcome : "unknown"}`),
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
            <Field.Label for={"imap-username-" + id}>
                {$_("settings.emailAccounts.new.credentials.username")}
            </Field.Label>
            <Input
                    bind:ref={firstField}
                    id={"imap-username-" + id}
                    type="text"
                    autocomplete="username"
                    placeholder="josh.smith@example.com"
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
                    placeholder={passwordPlaceholder}
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

    <!--
      What makes Enter submit at all: a form only does so implicitly when it owns a submit button,
      and the visible one lives in the dialog footer, disabled for exactly as long as the step has
      not checked out -- which is when Enter still has something to do, see `submit`.
    -->
    <button type="submit" class="sr-only" tabindex="-1" aria-hidden="true"></button>
</form>
