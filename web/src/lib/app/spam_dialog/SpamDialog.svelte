<script module lang="ts">
    import type {EmailCardParticipant, EmailCardTag} from "$lib/app/my-stack/EmailCard.svelte";

    /**
     * The mail the filter is being written for, in the shape the stack hands its cards -- so a
     * caller that already has one of those can pass it straight in.
     */
    export type SpamDialogMail = {
        /** Which mail this is: what the rule is checked against, see `MailRepository`. */
        id: string;
        sender: EmailCardParticipant & {avatarUrl?: string};
        /** Formatted for display already, see `EmailCard`. */
        sent: string;
        to: EmailCardParticipant[];
        cc?: EmailCardParticipant[];
        bcc?: EmailCardParticipant[];
        subject: string;
        tags?: EmailCardTag[];
        /** Absent while the body's own request is still out; the card then shows its shape. */
        body?: string;
        /** The mail's HTML part, see `EmailCard`. */
        html?: string;
    };
</script>

<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import * as Field from "$lib/components/ui/field";
    import {Button} from "$lib/components/ui/button";
    import {ButtonGroup} from "$lib/components/ui/button-group";
    import {Input} from "$lib/components/ui/input";
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import SpamRuleEditor from "./SpamRuleEditor.svelte";
    import {mailRepository} from "$lib/repository/MailRepository";
    import {filterRepository, type SpamFilterRecord} from "$lib/repository/FilterRepository";
    import SaveFilterDialog from "./SaveFilterDialog.svelte";
    import {cn} from "$lib/utils.js";
    import type {RuleReadout, SpamRule} from "./rule";

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
        /** The filter, once the server has it. Only ever called with a rule that adds up. */
        onsubmit?: (filter: SpamFilterRecord) => void;
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

    /**
     * Whether the rule as it stands would catch the mail on screen. `unknown` while there is no
     * rule to ask about, or no mail to ask against.
     */
    let hit = $state<"unknown" | "match" | "miss" | "error">("unknown");

    /** Whether an answer is on its way. The one before it stays on screen until it arrives. */
    let isChecking = $state(false);

    $effect(() => {
        const rule = readout.rule;
        const id = mail?.id;

        if (!open || !rule || !id) {
            hit = "unknown";
            isChecking = false;
            return;
        }

        // Every change, without waiting for a pause in the typing: the question costs one small
        // request, and the answer is worth most while the rule is being written. Whatever the next
        // change overtakes is dropped below.
        const request = new AbortController();
        isChecking = true;

        mailRepository
            .validateRule(id, rule, request.signal)
            .then((matches) => {
                hit = matches ? "match" : "miss";
                isChecking = false;
            })
            .catch(() => {
                // A rule that was overtaken while its answer was on its way is not an error; the
                // check that overtook it is already on its way.
                if (request.signal.aborted) return;
                hit = "error";
                isChecking = false;
            });

        return () => request.abort();
    });

    // A dialog that opens again starts over -- the editor is rebuilt from `initial` either way, so
    // a half-typed name, a question about the last rule or the trouble it ran into would all be
    // left over from a filter nobody is writing any more.
    $effect(() => {
        if (open) {
            name = "";
            panel = "rule";
            affected = null;
            trouble = null;
        }
    });

    /**
     * Whether a request of the save is out. Holds both dialogs' buttons, so nothing is saved twice
     * and the answer cannot arrive after the dialog is gone.
     */
    let busy = $state(false);

    /** What went wrong while saving, in the words the status line shows. */
    let trouble = $state<string | null>(null);

    /**
     * How many mails besides this one the rule catches, once that has been asked. Null while it has
     * not been -- and what opens the second dialog when it turns out to be more than none.
     */
    let affected = $state<number | null>(null);

    /**
     * Saving asks the mailbox first: a rule written while reading one mail may well catch thirty
     * others, and filing those is not something to do behind the reader's back. Nothing is written
     * by the asking, so the dialog it opens can still be called off.
     */
    async function submit() {
        if (!readout.rule || !ready || busy) return;

        busy = true;
        trouble = null;

        try {
            const others = await filterRepository.countAffectedMails(readout.rule, mail?.id);
            if (others > 0) {
                affected = others;
                return;
            }

            await save(false);
        } catch {
            trouble = "Der Filter konnte nicht gespeichert werden.";
        } finally {
            busy = false;
        }
    }

    /**
     * Writes the filter, and holds it against the mails that are already there when asked to.
     *
     * The mail goes along: it was flagged as spam before this filter existed, so the server hands
     * that flag over to the filter -- the filter is the reason for it, not the reader's own hand.
     */
    async function save(retroactively: boolean) {
        const rule = readout.rule;
        if (!rule) return;

        const filter = await filterRepository.createFilter({
            name: name.trim(),
            rule,
            mail: mail?.id,
        });

        if (retroactively) await filterRepository.applyFilter(filter.id);

        onsubmit?.(filter);
        affected = null;
        open = false;
    }

    /** The two answers of the second dialog, both of which save. */
    async function saveFromDialog(retroactively: boolean) {
        if (busy) return;

        busy = true;
        trouble = null;

        try {
            await save(retroactively);
        } catch {
            affected = null;
            trouble = "Der Filter konnte nicht gespeichert werden.";
        } finally {
            busy = false;
        }
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

            <!-- What the rule does to the mail beside it, asked of the server after every change.
                 The height is held whether or not there is something to say, so the panels above
                 do not jump every time the answer comes and goes. -->
            {#if mail}
                <p class="min-h-5 text-sm text-muted-foreground" aria-live="polite">
                    {#if hit === "match"}
                        <span class="text-foreground">Diese Regel würde diese E-Mail als Spam einsortieren.</span>
                    {:else if hit === "miss"}
                        Diese Regel greift bei dieser E-Mail nicht.
                    {:else if trouble}
                        {trouble}
                    {:else if busy}
                        Wird gespeichert …
                    {:else if hit === "error"}
                        Die Regel konnte nicht geprüft werden.
                    {:else if isChecking}
                        Wird geprüft …
                    {/if}
                </p>
            {/if}
        </form>

        <Dialog.Footer>
            <Button variant="secondary" onclick={skip}>
                Weiter ohne Spamfilter
            </Button>

            <Button variant="default" disabled={!ready || busy} onclick={submit}>
                Spamfilter erstellen
            </Button>
        </Dialog.Footer>
    </Dialog.Content>
</Dialog.Root>

<!-- Only ever up when the rule catches more than the mail it was written for. Cancelling it leaves
     the editor as it was: nothing has been written at that point. The count is the state, and the
     binding reads and clears it -- a boolean beside it would be a second thing to keep in step. -->
<SaveFilterDialog
        bind:open={() => affected !== null, (isOpen) => { if (!isOpen) affected = null; }}
        count={affected ?? 0}
        {busy}
        onsave={() => saveFromDialog(false)}
        onapply={() => saveFromDialog(true)}
/>
