<script module lang="ts">
    import type {EmailCardParticipant} from "$lib/app/my-stack/EmailCard.svelte";

    /**
     * The mail the filter is being written for, in the shape the stack hands its cards -- so a
     * caller that already has one of those can pass it straight in.
     */
    export type SpamDialogMail = {
        sender: EmailCardParticipant & {avatarUrl?: string};
        /** Formatted for display already, see `EmailCard`. */
        sent: string;
        to: EmailCardParticipant[];
        cc?: EmailCardParticipant[];
        bcc?: EmailCardParticipant[];
        subject: string;
        tags?: string[];
        /** Absent while the body's own request is still out; the card then shows its shape. */
        body?: string;
    };
</script>

<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Kbd} from "$lib/components/ui/kbd";
    import {Button} from "$lib/components/ui/button";
    import {ButtonGroup} from "$lib/components/ui/button-group";
    import {Input} from "$lib/components/ui/input";
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import SpamRuleEditor from "./SpamRuleEditor.svelte";
    import {cn} from "$lib/utils.js";
    import type {RuleReadout, SpamFilter, SpamRule} from "./rule";

    let {
        open = $bindable(),
        initial = null,
        mail,
        onsubmit,
        onskip
    }: {
        open?: boolean;
        /**
         * The rule the editor opens with — for a filter that is being changed, or one put together
         * from the mail that brought the dialog up. Null starts with an empty rule.
         */
        initial?: SpamRule | null;
        /** The mail to write the filter against. Without one the rule has the dialog to itself. */
        mail?: SpamDialogMail;
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

    /** Which of the two panels is on screen. Only asked below a desktop, where they take turns. */
    let panel = $state<"rule" | "mail">("rule");

    // Both halves have to be there: a rule that adds up, and a name to find the filter under.
    const ready = $derived(readout.rule !== null && name.trim().length > 0);

    // A dialog that opens again starts over -- the editor is rebuilt from `initial` either way, so
    // leaving a half-typed name from last time behind would be the odd one out.
    $effect(() => {
        if (open) {
            name = "";
            panel = "rule";
        }
    });

    function submit() {
        if (!readout.rule || !ready) return;

        onsubmit?.({name: name.trim(), rule: readout.rule});
        open = false;
    }

    function skip() {
        onskip?.();
        open = false;
    }

    /** Hidden below a desktop unless it is the panel that was picked; side by side from there on. */
    function panelClass(which: "rule" | "mail"): string {
        return mail && panel !== which ? "hidden xl:block" : "";
    }
</script>

<Dialog.Root bind:open={open}>
    <!-- Room is what this dialog needs: the whole screen up to a desktop, and all of it but 32px
         from there on. The rows are spelled out so that the panels take everything the header and
         the buttons leave.

         The geometry is an inline style because the classes centre the dialog with a transform,
         which a box pinned to all four sides does not need -- and `translate: none` is the only
         way to be rid of it, since the class stays in the list.

         No focus trap: it refocuses whatever it last remembered as soon as that element leaves the
         dialog, and Blockly takes its field editors apart when they close. The trap would then
         pull focus to the name field behind Blockly's back, every time a value is edited. Blockly
         keeps focus inside its canvas by itself; what is lost is Tab stopping at the dialog's
         edge. -->
    <Dialog.Content
            class="inset-0 grid-rows-[auto_1fr_auto] h-auto w-auto max-w-none rounded-none sm:max-w-none xl:inset-8 xl:rounded-4xl"
            style="translate: none;"
            trapFocus={false}
    >
        <Dialog.Header>
            <Dialog.Title>Neuer Spamfilter</Dialog.Title>
            <Dialog.Description>Automatisch ähnliche E-Mails als Spam herausfiltern</Dialog.Description>
        </Dialog.Header>

        <form class="flex min-h-0 flex-col gap-5" onsubmit={(event) => {event.preventDefault(); submit();}}>
            <Field.Field>
                <Field.Label for={id + "-name"}>Name</Field.Label>
                <Input id={id + "-name"} bind:value={name} placeholder="Filtername" />
            </Field.Field>

            <!-- Two panels, one screen: below a desktop they take turns, and this is the switch.
                 A desktop shows both, so the switch goes with the choice it stands for. -->
            {#if mail}
                <ButtonGroup role="tablist" class="w-full xl:hidden">
                    <Button
                            variant={panel === "rule" ? "secondary" : "outline"}
                            class="flex-1"
                            role="tab"
                            aria-selected={panel === "rule"}
                            onclick={() => (panel = "rule")}
                    >
                        Regel
                    </Button>
                    <Button
                            variant={panel === "mail" ? "secondary" : "outline"}
                            class="flex-1"
                            role="tab"
                            aria-selected={panel === "mail"}
                            onclick={() => (panel = "mail")}
                    >
                        E-Mail
                    </Button>
                </ButtonGroup>
            {/if}

            <div class="flex min-h-0 flex-1 flex-col gap-4 xl:flex-row">
                <!-- The editor stays mounted whichever panel is showing: it is a workspace with the
                     user's blocks in it, not a view that can be thrown away and built again. -->
                <SpamRuleEditor
                        {initial}
                        onchange={(next) => (readout = next)}
                        class={cn("min-h-0 min-w-0 flex-1", panelClass("rule"))}
                />

                {#if mail}
                    <div
                            class={cn(
                                "min-h-0 min-w-0 flex-1 overflow-y-auto rounded-2xl border xl:w-[30%] xl:flex-none",
                                panelClass("mail"),
                            )}
                    >
                        <EmailCard {...mail} class="w-full shadow-none" />
                    </div>
                {/if}
            </div>
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
