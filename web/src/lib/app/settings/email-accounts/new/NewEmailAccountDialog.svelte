<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import {NewEmailAccountViewModel} from "$lib/app/settings/email-accounts/new/NewEmailAccountViewModel.svelte.ts";
    import {Spinner} from "$lib/components/ui/spinner/index.ts";

    let {
        open = $bindable(false),
    }: {
        open: boolean,
    } = $props();

    const id = $props.id();

    const viewModel = new NewEmailAccountViewModel();
</script>

<Dialog.Root bind:open>
    <Dialog.Content>
        <Dialog.Header>
            <Dialog.Title>Neues Postfach</Dialog.Title>
            <Dialog.Description>Verbinde deinen E-Mail-Anbieter, um E-Mails in Overmail zu laden.</Dialog.Description>
        </Dialog.Header>

        <div class="flex flex-col flex-1">
            <form>
                <Field.Group>
                    <Field.Set>
                        <Field.Legend>E-Mail-Anbieter</Field.Legend>
                        <Field.Description>
                            Informationen zur IMAP-Verbindung. Diese bekommst du bei deinem Anbieter.<br />
                            Hinweis: Overmail benötigt eine TLS-Verbindung.
                        </Field.Description>

                        <Field.Group class="flex flex-row gap-4">
                            <Field.Field>
                                <Field.Label for={"imap-host-" + id}>IMAP-Server</Field.Label>
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
                                <Field.Label for={"imap-port-" + id}>Port</Field.Label>
                                <Input
                                        id={"imap-port-" + id}
                                        type="number"
                                        placeholder="993"
                                        bind:value={
                                            () => viewModel.port,
                                            (port: number) => viewModel.setPort(port)
                                        }
                                />
                            </Field.Field>
                        </Field.Group>
                    </Field.Set>

                    {#if viewModel.imapServerTest.type === "testing"}
                        <div class="flex flex-row items-center gap-2">
                            <Spinner class="h-4 w-4" />
                            <span class="text-muted-foreground text-sm">Verbinde mit IMAP-Server...</span>
                        </div>
                    {/if}
                </Field.Group>
            </form>
        </div>

        <Dialog.Footer>
            <Button
                    variant="secondary"
                    onclick={() => open = false}
            >Abbrechen</Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>