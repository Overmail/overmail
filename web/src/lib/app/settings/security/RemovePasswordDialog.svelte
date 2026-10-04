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
        onremoved,
    }: {
        open: boolean;
        onremoved: () => void;
    } = $props();

    const {password: passwordRepository} = useRepositories();
    const id = $props.id();

    let current = $state("");
    let removing = $state(false);
    let failure: PasswordFailure | null = $state(null);

    $effect(() => {
        if (!open) return;
        current = "";
        failure = null;
    });

    async function remove() {
        if (removing || !current) return;
        removing = true;
        failure = null;
        try {
            await passwordRepository.remove(current);
            open = false;
            onremoved();
        } catch (error) {
            failure = error instanceof PasswordError ? error.failure : "failed";
        } finally {
            removing = false;
        }
    }
</script>

<Dialog.Root bind:open={() => open, (value) => { if (!removing) open = value; }}>
    <Dialog.Content class="sm:max-w-md">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.security.password.remove.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.security.password.remove.description")}</Dialog.Description>
        </Dialog.Header>

        <form
                id={"password-remove-form-" + id}
                onsubmit={(event) => {
                    event.preventDefault();
                    void remove();
                }}
        >
            <Field.Field data-invalid={failure === "current_password" || undefined}>
                <Field.Label for={"password-remove-current-" + id}>
                    {$_("settings.security.password.fields.current")}
                </Field.Label>
                <Input
                        id={"password-remove-current-" + id}
                        type="password"
                        autocomplete="current-password"
                        aria-invalid={failure === "current_password"}
                        bind:value={current}
                        oninput={() => { if (failure === "current_password") failure = null; }}
                />
                {#if failure === "current_password"}
                    <Field.Error>{$_("settings.security.password.errors.currentWrong")}</Field.Error>
                {:else if failure === "failed"}
                    <Field.Error>{$_("settings.security.password.errors.failed")}</Field.Error>
                {/if}
            </Field.Field>
        </form>

        <Dialog.Footer>
            <Button variant="secondary" disabled={removing} onclick={() => (open = false)}>
                {$_("settings.security.password.cancel")}
            </Button>
            <Button type="submit" variant="destructive" form={"password-remove-form-" + id} disabled={removing || !current}>
                {#if removing}<Spinner />{/if}
                {$_("settings.security.password.remove.confirm")}
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
