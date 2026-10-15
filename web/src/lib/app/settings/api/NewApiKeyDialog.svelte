<script lang="ts">
    import {onDestroy} from "svelte";
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";
    import {CheckIcon, CopyIcon, WarningCircleIcon} from "phosphor-svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {_} from "svelte-i18n";

    /** The longest name the server takes. */
    const MAX_NAME_LENGTH = 100;

    /** How long the button says "Copied" before it offers to copy again. */
    const COPIED_FLASH_MS = 2000;

    let {
        open = $bindable(false),
        onCreated,
    }: {
        open: boolean,
        /** Called once the key exists, so whoever lists them can re-read. */
        onCreated?: () => void,
    } = $props();

    const {apiKeys} = useRepositories();

    let name = $state("");
    let step: {type: "idle"} | {type: "saving"} | {type: "failed"} | {type: "created"; key: string} =
        $state({type: "idle"});

    let copied = $state(false);
    let copyFailed = $state(false);
    let copiedTimeout: ReturnType<typeof setTimeout> | null = null;

    // `$derived.by`, not `$derived`: read straight after the declaration, `step` is still
    // narrowed to the type it was initialised with, and the comparison looks impossible.
    const saving = $derived.by(() => step.type === "saving");
    const canSubmit = $derived.by(() => !saving && name.trim().length > 0 && name.trim().length <= MAX_NAME_LENGTH);

    // The dialog is kept mounted, so without this the next one would open on the key that was
    // just shown -- which must not be seen a second time, not even by accident.
    $effect(() => {
        if (open) return;
        name = "";
        step = {type: "idle"};
        copied = false;
        copyFailed = false;
    });

    async function create() {
        if (!canSubmit) return;

        step = {type: "saving"};
        try {
            const created = await apiKeys.create(name.trim());
            step = {type: "created", key: created.key};
            onCreated?.();
        } catch {
            step = {type: "failed"};
        }
    }

    async function copy(key: string) {
        try {
            await navigator.clipboard.writeText(key);
        } catch {
            copied = false;
            copyFailed = true;
            return;
        }
        copyFailed = false;
        copied = true;
        if (copiedTimeout) clearTimeout(copiedTimeout);
        copiedTimeout = setTimeout(() => {
            copied = false;
            copiedTimeout = null;
        }, COPIED_FLASH_MS);
    }

    onDestroy(() => {
        if (copiedTimeout) clearTimeout(copiedTimeout);
    });
</script>

<Dialog.Root bind:open>
    <Dialog.Content class="sm:max-w-lg">
        {#if step.type === "created"}
            {@const key = step.key}
            <Dialog.Header>
                <Dialog.Title>{$_("settings.api.new.created.title")}</Dialog.Title>
                <Dialog.Description>{$_("settings.api.new.created.description")}</Dialog.Description>
            </Dialog.Header>

            <div class="flex flex-col gap-2">
                <!-- Read-only rather than plain text, so a click selects the whole key at once. -->
                <div class="flex flex-row gap-2">
                    <Input
                            readonly
                            value={key}
                            class="font-mono text-xs"
                            aria-label={$_("settings.api.new.created.key")}
                            onfocus={(event) => event.currentTarget.select()}
                    />
                    <Button variant="outline" onclick={() => void copy(key)}>
                        {#if copied}
                            <CheckIcon />
                            {$_("settings.api.new.created.copied")}
                        {:else}
                            <CopyIcon />
                            {$_("settings.api.new.created.copy")}
                        {/if}
                    </Button>
                </div>
                {#if copyFailed}
                    <span class="text-destructive text-sm">{$_("settings.api.new.created.copyFailed")}</span>
                {/if}
                <p class="text-muted-foreground flex flex-row items-start gap-2 text-sm">
                    <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                    <span>{$_("settings.api.new.created.once")}</span>
                </p>
            </div>

            <Dialog.Footer>
                <Button onclick={() => (open = false)}>{$_("settings.api.new.created.done")}</Button>
            </Dialog.Footer>
        {:else}
            <Dialog.Header>
                <Dialog.Title>{$_("settings.api.new.title")}</Dialog.Title>
                <Dialog.Description>{$_("settings.api.new.description")}</Dialog.Description>
            </Dialog.Header>

            <!-- A form, so Enter in the name field makes the key, like the footer button does. -->
            <form
                    class="flex flex-col gap-4"
                    onsubmit={(event) => {
                        event.preventDefault();
                        void create();
                    }}
            >
                <Field.Field>
                    <Field.Label for="api-key-name">{$_("settings.api.new.name")}</Field.Label>
                    <Input
                            id="api-key-name"
                            type="text"
                            maxlength={MAX_NAME_LENGTH}
                            disabled={saving}
                            placeholder={$_("settings.api.new.namePlaceholder")}
                            bind:value={name}
                    />
                    <Field.Description>{$_("settings.api.new.nameHint")}</Field.Description>
                </Field.Field>

                <div aria-live="polite" class="min-h-5 text-sm">
                    {#if step.type === "saving"}
                        <div class="text-muted-foreground flex flex-row items-start gap-2">
                            <Spinner class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("settings.api.new.saving")}</span>
                        </div>
                    {:else if step.type === "failed"}
                        <div class="text-destructive flex flex-row items-start gap-2">
                            <WarningCircleIcon class="mt-0.5 size-4 shrink-0" />
                            <span>{$_("settings.api.new.failed")}</span>
                        </div>
                    {/if}
                </div>

                <Dialog.Footer>
                    <Button type="button" variant="secondary" disabled={saving} onclick={() => (open = false)}>
                        {$_("settings.api.new.cancel")}
                    </Button>
                    <Button type="submit" disabled={!canSubmit}>
                        {$_("settings.api.new.submit")}
                    </Button>
                </Dialog.Footer>
            </form>
        {/if}
    </Dialog.Content>
</Dialog.Root>
