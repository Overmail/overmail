<script lang="ts">
    // What the agent is up to, as a card at the top of the panel rather than as its content: the
    // chat below is what the window is for. Every number here is this user's share of the one
    // queue that serves the whole installation.
    import MailUserAvatar from "$lib/app/mails/table/MailUserAvatar.svelte";
    import type {AgentProcessStatus, AgentStep} from "$lib/repository/AgentRepository";

    let {status}: {status: AgentProcessStatus | null} = $props();

    const queue = $derived(status?.queue ?? null);
    const total = $derived(queue ? queue.processed + queue.queued : 0);
    const percent = $derived(total === 0 ? 0 : Math.round(((queue?.processed ?? 0) / total) * 100));
    const numberFormat = new Intl.NumberFormat();

    /** What the agent is doing to the mail at hand, one pass at a time. */
    const STEP_VERBS: Record<AgentStep, string> = {
        origin: 'Analysiere',
        tags: 'Tagge',
        thread: 'Kontextualisiere',
        review: 'Prüfe',
    };

    /** The mail in the agent's hands, and only ever one of ours -- see AgentWorkStatus. */
    const current = $derived.by(() => {
        const work = status?.work;
        if (!work?.sender) return null;

        return {
            sender: work.sender,
            subject: work.subject,
            // A step the client does not know yet is left out rather than guessed at.
            verb: work.step ? STEP_VERBS[work.step] : null,
        };
    });

    /**
     * What the card says the agent is up to. The button has room for two states, this has room for
     * the case at hand: one agent serves the whole installation, so it can be busy without being
     * busy with anything of ours, and it can be idle with our mails still waiting.
     */
    const detail = $derived.by(() => {
        if (!status || !queue) return {title: 'Noch keine Verbindung', note: null};

        switch (status.work.state) {
            case 'pending':
                return {
                    title: 'Arbeitet an einer fremden Mail',
                    note: 'Ein Agent bedient die ganze Installation, deine Mails sind als Nächstes dran.',
                };
            case 'processing':
                return {
                    title: queue.mode === 'backlog' ? 'Importiert deine Mailbox' : 'Verarbeitet eine neue Mail',
                    note: null,
                };
            default:
                return queue.queued === 0
                    ? {title: 'Alles verarbeitet', note: 'Der Agent wartet auf neue Mails.'}
                    : {title: 'Wartet', note: 'Der Agent arbeitet die offenen Mails gerade nicht ab.'};
        }
    });
</script>

<div class="mx-3 flex shrink-0 flex-col gap-3 rounded-2xl border p-3">
    <div class="flex flex-col gap-0.5">
        <span class="text-sm font-medium">{detail.title}</span>
        {#if detail.note}
            <span class="text-muted-foreground text-xs">{detail.note}</span>
        {/if}
    </div>

    {#if current}
        <div class="flex items-center gap-2 text-xs" title={current.sender.address}>
            {#if current.verb}
                <span class="shrink-0 font-medium">{current.verb}</span>
            {/if}
            <MailUserAvatar participant={current.sender} />
            <!-- min-w-0 lets the flex child shrink, without it truncate never kicks in. -->
            <span class="min-w-0 truncate">{current.subject?.trim() || 'Ohne Betreff'}</span>
        </div>
    {/if}

    {#if queue && total > 0}
        <div class="flex flex-col gap-1.5">
            <div class="bg-muted h-1.5 w-full overflow-hidden rounded-full">
                <div class="bg-primary h-full transition-[width]" style="width: {percent}%"></div>
            </div>
            <div class="flex flex-row items-center justify-between gap-1 text-xs text-muted-foreground">
                <span>{percent}%</span>
                <span>{numberFormat.format(queue.processed)} / {numberFormat.format(total)}</span>
            </div>
        </div>
    {/if}
</div>
