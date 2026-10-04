<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as InputOTP from "$lib/components/ui/input-otp";
    import {Button} from "$lib/components/ui/button";
    import {Spinner} from "$lib/components/ui/spinner";
    import QRCode from "@castlenine/svelte-qrcode";
    import {CheckIcon, CopyIcon, WarningCircleIcon} from "phosphor-svelte";
    import {_} from "svelte-i18n";
    import {untrack} from "svelte";
    import {useRepositories} from "$lib/repository/repositories";
    import {TotpError, type TotpSetup} from "$lib/repository/TotpRepository";

    let {
        open = $bindable(false),
        onenabled,
    }: {
        open: boolean;
        onenabled: () => void;
    } = $props();

    const CODE_LENGTH = 6;

    const {totp: totpRepository} = useRepositories();

    let setup: {type: "loading"} | {type: "ready"; value: TotpSetup} | {type: "failed"} = $state({type: "loading"});
    let code = $state("");
    let verifying = $state(false);
    let failure: "code" | "failed" | null = $state(null);
    let copied = $state(false);

    // A fresh secret for every opening: one that was shown and abandoned is never stored, so
    // there is nothing to reuse.
    $effect(() => {
        if (!open) return;
        code = "";
        failure = null;
        copied = false;
        untrack(() => void requestSetup());
    });

    async function requestSetup() {
        setup = {type: "loading"};
        try {
            setup = {type: "ready", value: await totpRepository.setup()};
        } catch {
            setup = {type: "failed"};
        }
    }

    async function copySecret(secret: string) {
        await navigator.clipboard.writeText(secret);
        copied = true;
        setTimeout(() => (copied = false), 2000);
    }

    async function verify() {
        if (verifying || setup.type !== "ready" || code.length < CODE_LENGTH) return;
        verifying = true;
        failure = null;
        try {
            await totpRepository.enable(setup.value.secret, code);
            open = false;
            onenabled();
        } catch (error) {
            // A wrong code keeps the secret: the app has it already, only the code was off.
            failure = error instanceof TotpError && error.failure === "code" ? "code" : "failed";
            code = "";
        } finally {
            verifying = false;
        }
    }

    // Checked as soon as the last digit is in, like the sign-in does.
    $effect(() => {
        if (code.length === CODE_LENGTH) untrack(() => void verify());
    });
</script>

<Dialog.Root bind:open={() => open, (value) => { if (!verifying) open = value; }}>
    <Dialog.Content class="sm:max-w-xl">
        <Dialog.Header>
            <Dialog.Title>{$_("settings.security.totp.setup.title")}</Dialog.Title>
            <Dialog.Description>{$_("settings.security.totp.setup.description")}</Dialog.Description>
        </Dialog.Header>

        {#if setup.type === "loading"}
            <div class="flex h-40 items-center justify-center">
                <Spinner class="size-6" />
            </div>
        {:else if setup.type === "failed"}
            <div class="flex flex-row items-center gap-2 text-sm text-destructive">
                <WarningCircleIcon class="size-4 shrink-0" />
                {$_("settings.security.totp.setup.loadFailed")}
                <button class="cursor-pointer underline underline-offset-2" onclick={() => void requestSetup()}>
                    {$_("settings.security.totp.retry")}
                </button>
            </div>
        {:else}
            {@const secret = setup.value.secret}
            <div class="flex w-full flex-col gap-3">
                <div class="flex flex-row flex-wrap items-center justify-center gap-6">
                    <div class="size-40 shrink-0 overflow-hidden rounded-md">
                        <QRCode data={setup.value.uri} size={160} shape="circle" />
                    </div>

                    <div class="h-32 w-px bg-muted max-sm:hidden"></div>

                    <div class="flex min-w-0 flex-col items-center gap-2">
                        <span class="text-center text-sm text-muted-foreground">{$_("settings.security.totp.setup.orKey")}</span>
                        <div class="flex flex-row items-center gap-2">
                            <!-- In groups of four, as apps print it and as it is easiest to type. -->
                            <span class="w-fit break-all rounded-md bg-accent px-1.5 py-0.5 font-mono text-sm text-accent-foreground">
                                {secret.match(/.{1,4}/g)?.join(" ")}
                            </span>
                            <Button
                                    variant="outline"
                                    size="icon-sm"
                                    aria-label={$_("settings.security.totp.setup.copy")}
                                    onclick={() => void copySecret(secret)}
                            >
                                {#if copied}<CheckIcon />{:else}<CopyIcon />{/if}
                            </Button>
                        </div>
                    </div>
                </div>

                <div class="flex w-full flex-col items-center gap-2">
                    <span class="text-sm">{$_("settings.security.totp.setup.enterCode")}</span>

                    <InputOTP.Root
                            bind:value={code}
                            maxlength={CODE_LENGTH}
                            pattern={"^\\d+$"}
                            disabled={verifying}
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
            </div>
        {/if}

        <Dialog.Footer>
            <Button variant="outline" disabled={verifying} onclick={() => (open = false)}>
                {$_("settings.security.totp.cancel")}
            </Button>
            <Button disabled={verifying || code.length !== CODE_LENGTH} onclick={() => void verify()}>
                {#if verifying}<Spinner />{/if}
                {$_("settings.security.totp.setup.confirm")}
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
