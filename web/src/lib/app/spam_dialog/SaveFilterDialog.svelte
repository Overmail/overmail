<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";

    let {
        open = $bindable(false),
        count,
        busy = false,
        onsave,
        onapply
    }: {
        open?: boolean;
        /** How many mails besides the one on screen the rule catches. Only asked about when > 0. */
        count: number;
        /** Whether the save that was asked for is still on its way. */
        busy?: boolean;
        /** Save the filter and leave the mailbox as it is. */
        onsave: () => void;
        /** Save it and file everything it catches as spam. */
        onapply: () => void;
    } = $props();

    const mails = $derived(count === 1 ? "eine weitere E-Mail" : `${count} weitere E-Mails`);
</script>

<!-- Its own dialog on top of the editor rather than a third button down there: this is a question
     about the mailbox, and it is asked once, at the moment of saving. -->
<Dialog.Root bind:open={open}>
    <Dialog.Content class="sm:max-w-lg" showCloseButton={false}>
        <Dialog.Header>
            <Dialog.Title>Der Filter trifft {mails}</Dialog.Title>
            <Dialog.Description>
                Gespeichert gilt der Filter für alles, was neu ankommt. Rückwirkend angewendet
                sortiert er {mails} sofort als Spam ein.
            </Dialog.Description>
        </Dialog.Header>

        <Dialog.Footer>
            <Button variant="secondary" disabled={busy} onclick={() => (open = false)}>
                Abbrechen
            </Button>

            <Button variant="outline" disabled={busy} onclick={onsave}>
                Nur speichern
            </Button>

            <Button variant="default" disabled={busy} onclick={onapply}>
                Speichern und rückwirkend anwenden
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
