<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Kbd} from "$lib/components/ui/kbd";
    import {Button} from "$lib/components/ui/button";
    import {Input} from "$lib/components/ui/input";
    import SpamRuleEditor from "./SpamRuleEditor.svelte";
    import type {RuleReadout, SpamFilter, SpamRule} from "./rule";

    let {
        open = $bindable(),
        initial = null,
        onsubmit,
        onskip
    }: {
        open?: boolean;
        /**
         * The rule the editor opens with — for a filter that is being changed, or one put together
         * from the mail that brought the dialog up. Null starts with an empty rule.
         */
        initial?: SpamRule | null;
        /** The finished filter. Only ever called with a rule that adds up. */
        onsubmit?: (filter: SpamFilter) => void;
        /** The dialog was closed without a filter. */
        onskip?: () => void;
    } = $props();

    let id = $props.id();

    let name = $state("");

    // What the blocks currently say. The editor reports it once on startup, so the state below is
    // only what it takes to render before that reply arrives.
    let readout = $state<RuleReadout>({rule: null, problem: null, warning: null});

    // Both halves have to be there: a rule that adds up, and a name to find the filter under.
    const ready = $derived(readout.rule !== null && name.trim().length > 0);

    function submit() {
        if (!readout.rule || !ready) return;

        onsubmit?.({name: name.trim(), rule: readout.rule});
        open = false;
    }

    function skip() {
        onskip?.();
        open = false;
    }
</script>

<Dialog.Root bind:open={open}>
    <!-- Room is what this dialog needs: the whole screen up to a desktop, and all of it but 64px
         from there on. The rows are spelled out so that the canvas takes everything the header and
         the buttons leave.

         `translate: none` undoes the centring the classes do with a transform. A transform makes
         the dialog's box the frame of reference for everything positioned inside it, and Blockly's
         dropdowns and field editors are positioned in page coordinates — see the editor. Pinned to
         all four sides, the dialog needs no transform to sit where it should. -->
    <Dialog.Content
            class="inset-0 grid-rows-[auto_1fr_auto] h-auto w-auto max-w-none rounded-none sm:max-w-none xl:inset-16 xl:rounded-4xl"
            style="translate: none;"
    >
        <Dialog.Header>
            <Dialog.Title>Neuer Spamfilter</Dialog.Title>
            <Dialog.Description>Automatisch ähnliche E-Mails als Spam herausfiltern</Dialog.Description>
        </Dialog.Header>

        <form class="flex min-h-0 flex-col" onsubmit={(event) => {event.preventDefault(); submit();}}>
            <Field.Group class="min-h-0 flex-1">
                <Field.Field>
                    <Field.Label for={id + "-name"}>Name</Field.Label>
                    <Input id={id + "-name"} bind:value={name} placeholder="Filtername" />
                </Field.Field>

                <Field.Set class="min-h-0 flex-1">
                    <Field.Legend>Regel</Field.Legend>

                    <SpamRuleEditor
                            {initial}
                            onchange={(next) => (readout = next)}
                            class="min-h-0 flex-1"
                    />
                </Field.Set>
            </Field.Group>
        </form>

        <Dialog.Footer>
            <Button variant="secondary" onclick={skip}>
                <Kbd>Esc</Kbd>
                Weiter ohne Spamfilter
            </Button>

            <Button variant="default" disabled={!ready} onclick={submit}>
                Spamfilter erstellen
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>
