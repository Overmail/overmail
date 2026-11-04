<script lang="ts">
    import * as Dialog from "$lib/components/ui/dialog";
    import {Button} from "$lib/components/ui/button";
    import {Spinner} from "$lib/components/ui/spinner";
    import {_} from "svelte-i18n";
    import {goto} from "$app/navigation";
    import CheckCircleIcon from "phosphor-svelte/lib/CheckCircleIcon";
    import CopyIcon from "phosphor-svelte/lib/CopyIcon";
    import WarningCircleIcon from "phosphor-svelte/lib/WarningCircleIcon";
    import {useRepositories} from "$lib/repository/repositories";
    import {type ImportEmailResult, type Inbox, inboxLabel} from "$lib/repository/InboxRepository";
    import {emailPath} from "$lib/app/mails/emailPath";

    /**
     * Imports dropped `.eml` files into one of the user's mailboxes.
     *
     * With a single mailbox there is nothing to ask and the import starts at once; with several the
     * dialog asks which one first. Either way it stays open to say how every file went, since a
     * mail the inbox holds already or a file that is no mail is not visible anywhere else.
     */
    let {open = $bindable(false)}: {open?: boolean} = $props();

    const {inboxes: inboxRepository} = useRepositories();

    type Outcome = {file: File; result: ImportEmailResult | {type: "failed"}};

    type Phase =
        | {type: "loading"}
        | {type: "choose"; inboxes: Inbox[]}
        | {type: "importing"; inbox: Inbox; done: number}
        | {type: "done"; inbox: Inbox; outcomes: Outcome[]}
        | {type: "no_inbox"}
        | {type: "failed"};

    let phase = $state<Phase>({type: "loading"});
    let files: File[] = $state([]);

    const busy = $derived(phase.type === "loading" || phase.type === "importing");

    /** Starts over with [dropped]; ignored while an import is still running. */
    export async function start(dropped: File[]) {
        if (open && busy) return;
        files = dropped;
        phase = {type: "loading"};
        open = true;

        let inboxes: Inbox[];
        try {
            inboxes = await inboxRepository.list();
        } catch {
            phase = {type: "failed"};
            return;
        }

        if (inboxes.length === 0) phase = {type: "no_inbox"};
        else if (inboxes.length === 1) await importInto(inboxes[0]);
        else phase = {type: "choose", inboxes};
    }

    // One after the other: the server deduplicates per inbox, and two of the same mail racing
    // each other would both be new to it.
    async function importInto(inbox: Inbox) {
        const outcomes: Outcome[] = [];
        phase = {type: "importing", inbox, done: 0};
        for (const file of files) {
            try {
                outcomes.push({file, result: await inboxRepository.importEml(inbox.id, file)});
            } catch {
                outcomes.push({file, result: {type: "failed"}});
            }
            phase = {type: "importing", inbox, done: outcomes.length};
        }
        phase = {type: "done", inbox, outcomes};
    }

    function openMail(id: string) {
        open = false;
        void goto(emailPath(id));
    }

    const singleImported = $derived.by(() => {
        if (phase.type !== "done" || phase.outcomes.length !== 1) return null;
        const result = phase.outcomes[0].result;
        return result.type === "imported" ? result.id : null;
    });
</script>

<Dialog.Root bind:open={() => open, (value) => { if (!busy) open = value; }}>
    <Dialog.Content class="sm:max-w-md" showCloseButton={!busy}>
        <Dialog.Header>
            <Dialog.Title>{$_("app.emlImport.title", {values: {count: files.length}})}</Dialog.Title>
            <Dialog.Description>
                {#if phase.type === "choose"}
                    {$_("app.emlImport.choose.description", {values: {count: files.length}})}
                {:else if phase.type === "importing"}
                    {$_("app.emlImport.importing", {values: {inbox: inboxLabel(phase.inbox), done: phase.done, count: files.length}})}
                {:else if phase.type === "done"}
                    {$_("app.emlImport.done", {values: {inbox: inboxLabel(phase.inbox)}})}
                {:else if phase.type === "no_inbox"}
                    {$_("app.emlImport.noInbox")}
                {:else if phase.type === "failed"}
                    {$_("app.emlImport.loadFailed")}
                {:else}
                    {$_("app.emlImport.loading")}
                {/if}
            </Dialog.Description>
        </Dialog.Header>

        {#if phase.type === "loading" || phase.type === "importing"}
            <div class="flex justify-center py-4"><Spinner class="size-6" /></div>
        {:else if phase.type === "choose"}
            <!-- Scrolls on its own: an account may hold many inboxes, and the dialog must not grow
                 past the window. The padding keeps the focus ring inside what is clipped. -->
            <ul class="-mx-1 flex max-h-[min(20rem,50vh)] flex-col gap-2 overflow-y-auto p-1">
                {#each phase.inboxes as inbox (inbox.id)}
                    <li>
                        <button
                                type="button"
                                class="flex w-full cursor-pointer flex-col items-start rounded-2xl border px-4 py-3 text-start transition-colors hover:bg-accent focus-visible:bg-accent focus-visible:outline-none"
                                onclick={() => importInto(inbox)}
                        >
                            <span class="font-medium">{inboxLabel(inbox)}</span>
                            {#if inbox.name !== null}
                                <span class="text-xs text-muted-foreground">{inbox.username}</span>
                            {/if}
                        </button>
                    </li>
                {/each}
            </ul>
        {:else if phase.type === "done"}
            <ul class="flex max-h-72 flex-col gap-2 overflow-y-auto">
                {#each phase.outcomes as outcome, index (index)}
                    <li class="flex items-start gap-3">
                        {#if outcome.result.type === "imported"}
                            <CheckCircleIcon class="mt-0.5 size-5 shrink-0 text-primary" weight="fill" />
                        {:else if outcome.result.type === "already_exists"}
                            <CopyIcon class="mt-0.5 size-5 shrink-0 text-muted-foreground" />
                        {:else}
                            <WarningCircleIcon class="mt-0.5 size-5 shrink-0 text-destructive" weight="fill" />
                        {/if}
                        <div class="min-w-0 flex-1">
                            <p class="truncate font-medium">{outcome.file.name}</p>
                            <p class="text-xs text-muted-foreground">{$_(`app.emlImport.result.${outcome.result.type}`)}</p>
                        </div>
                        {#if outcome.result.type === "imported" && phase.outcomes.length > 1}
                            {@const id = outcome.result.id}
                            <Button variant="ghost" size="sm" onclick={() => openMail(id)}>
                                {$_("app.emlImport.open")}
                            </Button>
                        {/if}
                    </li>
                {/each}
            </ul>
        {/if}

        {#if !busy}
            <Dialog.Footer>
                <Button variant="secondary" onclick={() => (open = false)}>
                    {$_(phase.type === "choose" ? "app.emlImport.cancel" : "app.emlImport.close")}
                </Button>
                {#if singleImported}
                    {@const id = singleImported}
                    <Button onclick={() => openMail(id)}>{$_("app.emlImport.open")}</Button>
                {/if}
            </Dialog.Footer>
        {/if}
    </Dialog.Content>
</Dialog.Root>
