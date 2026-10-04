<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {Spinner} from "$lib/components/ui/spinner";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories";
    import {PasswordError, type PasswordFailure} from "$lib/repository/PasswordRepository";

    let {
        open = $bindable(false),
        isSet,
        onsaved,
    }: {
        open: boolean;
        /** Whether there is a password already: changing one asks for it first. */
        isSet: boolean;
        onsaved: () => void;
    } = $props();

    /** As the server's `PASSWORD_MIN_LENGTH`. */
    const MIN_LENGTH = 8;

    const {password: passwordRepository} = useRepositories();
    const id = $props.id();

    let current = $state("");
    let next = $state("");
    let repeated = $state("");
    let saving = $state(false);
    let failure: PasswordFailure | null = $state(null);

    // Told apart only once there is something to tell: a field being typed into is not wrong yet.
    const tooShort = $derived(next.length > 0 && next.length < MIN_LENGTH);
    const mismatch = $derived(repeated.length > 0 && repeated !== next);
    const canSave = $derived(
        !saving && (!isSet || current.length > 0) && next.length >= MIN_LENGTH && next === repeated,
    );

    // Every opening starts empty: a password must not wait in a closed dialog for the next one.
    $effect(() => {
        if (!open) return;
        current = "";
        next = "";
        repeated = "";
        failure = null;
    });

    async function save() {
        if (!canSave) return;
        saving = true;
        failure = null;
        try {
            await passwordRepository.set(next, isSet ? current : null);
            open = false;
            onsaved();
        } catch (error) {
            failure = error instanceof PasswordError ? error.failure : "failed";
        } finally {
            saving = false;
        }
    }
</script>

<Dialog.Root bind:open={() => open, (value) => { if (!saving) open = value; }}>
    <Dialog.Content class="sm:max-w-md">
        <Dialog.Header>
            <Dialog.Title>
                {isSet ? $_("settings.security.password.change.title") : $_("settings.security.password.set.title")}
            </Dialog.Title>
            <Dialog.Description>
                {isSet ? $_("settings.security.password.change.description") : $_("settings.security.password.set.description")}
            </Dialog.Description>
        </Dialog.Header>

        <!-- A form, so Enter in any field saves -- the same thing the footer button does. -->
        <form
                id={"password-form-" + id}
                class="flex flex-col gap-4"
                onsubmit={(event) => {
                    event.preventDefault();
                    void save();
                }}
        >
            {#if isSet}
                <Field.Field data-invalid={failure === "current_password" || undefined}>
                    <Field.Label for={"password-current-" + id}>
                        {$_("settings.security.password.fields.current")}
                    </Field.Label>
                    <Input
                            id={"password-current-" + id}
                            type="password"
                            autocomplete="current-password"
                            aria-invalid={failure === "current_password"}
                            bind:value={current}
                            oninput={() => { if (failure === "current_password") failure = null; }}
                    />
                    {#if failure === "current_password"}
                        <Field.Error>{$_("settings.security.password.errors.currentWrong")}</Field.Error>
                    {/if}
                </Field.Field>
            {/if}

            <Field.Field data-invalid={tooShort || failure === "new_password" || undefined}>
                <Field.Label for={"password-new-" + id}>{$_("settings.security.password.fields.new")}</Field.Label>
                <Input
                        id={"password-new-" + id}
                        type="password"
                        autocomplete="new-password"
                        aria-invalid={tooShort || failure === "new_password"}
                        bind:value={next}
                        oninput={() => { if (failure === "new_password") failure = null; }}
                />
                {#if failure === "new_password"}
                    <Field.Error>{$_("settings.security.password.errors.newRejected")}</Field.Error>
                {:else}
                    <Field.Description class={tooShort ? "text-destructive" : ""}>
                        {$_("settings.security.password.minLength", {values: {count: MIN_LENGTH}})}
                    </Field.Description>
                {/if}
            </Field.Field>

            <Field.Field data-invalid={mismatch || undefined}>
                <Field.Label for={"password-repeat-" + id}>{$_("settings.security.password.fields.repeat")}</Field.Label>
                <Input
                        id={"password-repeat-" + id}
                        type="password"
                        autocomplete="new-password"
                        aria-invalid={mismatch}
                        bind:value={repeated}
                />
                {#if mismatch}
                    <Field.Error>{$_("settings.security.password.errors.mismatch")}</Field.Error>
                {/if}
            </Field.Field>

            {#if failure === "failed"}
                <p role="alert" class="text-sm text-destructive">{$_("settings.security.password.errors.failed")}</p>
            {/if}
        </form>

        <Dialog.Footer>
            <Button variant="secondary" disabled={saving} onclick={() => (open = false)}>
                {$_("settings.security.password.cancel")}
            </Button>
            <Button type="submit" form={"password-form-" + id} disabled={!canSave}>
                {#if saving}<Spinner />{/if}
                {$_("settings.security.password.save")}
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
