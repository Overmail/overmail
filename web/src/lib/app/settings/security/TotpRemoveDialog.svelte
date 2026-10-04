<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as InputOTP from "$lib/components/ui/input-otp";
    import {Button} from "$lib/components/ui/button";
    import {Spinner} from "$lib/components/ui/spinner";
    import {_} from "svelte-i18n";
    import {untrack} from "svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {TotpError} from "$lib/repository/TotpRepository";

    let {
        open = $bindable(false),
        onremoved,
    }: {
        open: boolean;
        onremoved: () => void;
    } = $props();

    const CODE_LENGTH = 6;

    const {totp: totpRepository} = useRepositories();

    let code = $state("");
    let removing = $state(false);
    let failure: "code" | "failed" | null = $state(null);

    $effect(() => {
        if (!open) return;
        code = "";
        failure = null;
    });

    async function remove() {
        if (removing || code.length < CODE_LENGTH) return;
        removing = true;
        failure = null;
        try {
            await totpRepository.disable(code);
            open = false;
            onremoved();
        } catch (error) {
            failure = error instanceof TotpError && error.failure === "code" ? "code" : "failed";
            code = "";
        } finally {
            removing = false;
        }
    }
</script>

<Dialog.Root bind:open={() => open, (value) => { if (!removing) open = value; }}>
    <Dialog.Content class="sm:max-w-md">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.security.totp.remove.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.security.totp.remove.description")}</Dialog.Description>
        </Dialog.Header>

        <div class="flex flex-col items-center gap-2 py-2">
            <InputOTP.Root
                    bind:value={code}
                    maxlength={CODE_LENGTH}
                    pattern={"^\\d+$"}
                    disabled={removing}
                    aria-label={$_("settings.security.totp.code")}
                    onValueChange={() => { if (failure && code) failure = null; }}
                    autofocus
            >
                {#snippet children({cells})}
                    <InputOTP.Group>
                        {#each cells.slice(0, 3) as cell (cell)}
                            <InputOTP.Slot {cell} aria-invalid={failure === "code"} class="size-10 text-base" />
                        {/each}
                    </InputOTP.Group>
                    <InputOTP.Separator />
                    <InputOTP.Group>
                        {#each cells.slice(3, 6) as cell (cell)}
                            <InputOTP.Slot {cell} aria-invalid={failure === "code"} class="size-10 text-base" />
                        {/each}
                    </InputOTP.Group>
                {/snippet}
            </InputOTP.Root>

            {#if failure}
                <p role="alert" class="text-sm text-destructive">
                    {failure === "code" ? $_("settings.security.totp.errors.codeWrong") : $_("settings.security.totp.errors.failed")}
                </p>
            {/if}
        </div>

        <Dialog.Footer>
            <Button variant="outline" disabled={removing} onclick={() => (open = false)}>
                {$_("settings.security.totp.cancel")}
            </Button>
            <Button variant="destructive" disabled={removing || code.length !== CODE_LENGTH} onclick={() => void remove()}>
                {#if removing}<Spinner />{/if}
                {$_("settings.security.totp.remove.confirm")}
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
