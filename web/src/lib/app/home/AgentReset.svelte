<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {aiRepository, type AgentWorkReset} from "$lib/repository/AiRepository";

    // The button asks before it wipes anything: what it throws away is not coming back, and a
    // misplaced click on the home screen should not cost the whole classification.
    let isConfirming = $state(false);
    let isWorking = $state(false);
    let cleared = $state<AgentWorkReset | null>(null);
    let failed = $state(false);

    async function reset() {
        isWorking = true;
        failed = false;
        try {
            cleared = await aiRepository.resetAgentWork();
            isConfirming = false;
        } catch {
            failed = true;
        } finally {
            isWorking = false;
        }
    }
</script>

<section class="flex flex-col items-start gap-3">
    <div class="flex flex-col gap-0.5">
        <h2 class="text-sm font-medium">Agent</h2>
        <p class="text-muted-foreground max-w-prose text-xs">
            Stoppt den Processor und löscht alles, was der Agent angelegt hat: seine Tags, die
            Threads, die er eröffnet hat, und die Bearbeitungsstempel. Was du selbst getaggt hast,
            bleibt. Gilt für die ganze Installation, und der Processor bleibt bis zum nächsten
            Serverstart unten.
        </p>
    </div>

    {#if isConfirming}
        <div class="flex items-center gap-2">
            <Button variant="destructive" size="sm" disabled={isWorking} onclick={reset}>
                {isWorking ? "Wird gelöscht…" : "Ja, alles löschen"}
            </Button>
            <Button variant="ghost" size="sm" disabled={isWorking} onclick={() => (isConfirming = false)}>
                Abbrechen
            </Button>
        </div>
    {:else}
        <Button variant="destructive" size="sm" onclick={() => (isConfirming = true)}>
            Agent zurücksetzen
        </Button>
    {/if}

    {#if failed}
        <p class="text-destructive text-xs">Das Zurücksetzen ist fehlgeschlagen.</p>
    {:else if cleared}
        <!-- The counts as the server reports them: what came off the mails, and what was left
             without a mail afterwards and therefore went too. -->
        <p class="text-muted-foreground max-w-prose text-xs">
            {cleared.removed_tag_links} Tag-Zuordnungen und {cleared.removed_tags} Tags gelöscht,
            {cleared.removed_thread_links} Thread-Zuordnungen und {cleared.removed_threads} Threads
            gelöscht, {cleared.unstamped_mails} Mails sind wieder offen.
            {cleared.processor_was_running
                ? "Der Processor ist gestoppt."
                : "Der Processor lief nicht."}
        </p>
    {/if}
</section>
