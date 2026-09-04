import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import {
    MailLayout,
    foldGroups,
    type MailGrouping,
    type MailGroupCount,
} from "$lib/app/mails/grouping";

/** How many mails one request asks for. The server caps this at 500. */
const PAGE_SIZE = 100;

/**
 * The mailbox by position: which mail sits at which row, and where the headers between them go.
 *
 * Only positions and ids. What a row shows comes from the email repository, which this keeps
 * subscribed for the rows near the viewport and lets go of behind it -- the repository's grace
 * period is what makes that survive a flick of the scroll wheel.
 *
 * Two answers make the list: the stretches say how long it is and where the headers sit, which is
 * what lets a windowed table lay itself out before a single mail is loaded, and the pages fill in
 * which mail sits where. A hole is a mail that exists and has not been asked for; scrolling onto
 * one is what asks.
 */
export class MailListViewModel {
    /** The mailbox by position, newest first; undefined for a row not asked for yet. */
    private entries: Record<number, string> = $state({});

    /** How many mails the mailbox holds. 0 until the first page came back. */
    total = $state(0);

    /**
     * Whether a page has ever come back. An empty list means nothing until it has -- nothing was
     * asked for yet, and a mailbox with thousands in it would render as empty.
     */
    initialized = $state(false);

    failed = $state(false);

    /** Pages on their way or already here, by their offset, so no page is asked for twice. */
    private readonly pages = new Set<number>();

    /** The rows this holds a subscription for, by id. */
    private readonly subscribed = new Map<string, () => void>();

    /** The stretches as the server counted them, or null while they are on their way. */
    private groupCounts: MailGroupCount[] | null = $state(null);

    private isLoadingGroups = false;

    /**
     * Where every row sits. Until the stretches are here it is the mailbox without headers, so a
     * table can already page and draw.
     */
    layout: MailLayout = $derived(
        this.groupCounts === null
            ? MailLayout.flat(this.total)
            // Read as the layout is built rather than kept ticking: the stretches are re-read
            // when the list changes, and a table open at midnight is nobody's problem.
            : new MailLayout(foldGroups(this.groupCounts, new Date()))
    );

    constructor(
        private readonly mails: EmailRepository,
        private readonly grouping: MailGrouping = "date",
    ) {}

    /** The mail at [index], or undefined while that page is not here. */
    idAt(index: number): string | undefined {
        return this.entries[index];
    }

    /**
     * Makes sure the rows between [fromRow] and [toRow] -- rows of the layout, headers included --
     * are on their way and stay up to date. Cheap to call on every scroll frame: it asks for the
     * pages that are missing and nothing else.
     */
    window(fromRow: number, toRow: number) {
        void this.loadGroups();

        // Nothing is known before the first page, and it is that page which reports the length.
        if (!this.initialized) {
            void this.load(0);
            return;
        }

        const first = Math.max(0, Math.min(fromRow, toRow));
        const last = Math.max(fromRow, toRow);

        // Which mails those rows hold. The headers in between are the reason this is not the
        // range itself.
        let firstMail: number | undefined;
        let lastMail: number | undefined;
        for (let row = first; row <= last; row++) {
            const entry = this.layout.rowAt(row);
            if (entry?.kind !== "mail") continue;
            firstMail ??= entry.index;
            lastMail = entry.index;
        }

        if (firstMail === undefined || lastMail === undefined) return;

        for (
            let offset = Math.floor(firstMail / PAGE_SIZE) * PAGE_SIZE;
            offset <= lastMail;
            offset += PAGE_SIZE
        ) {
            void this.load(offset);
        }

        this.subscribeRange(firstMail, lastMail);
    }

    /** Asks again after a failure -- what the retry button does. */
    retry() {
        this.failed = false;
        this.pages.clear();
        this.groupCounts = null;
        this.isLoadingGroups = false;
        void this.loadGroups();
        void this.load(0);
    }

    /** Lets go of every row. The table calls this when it goes away. */
    dispose() {
        this.subscribed.forEach((release) => release());
        this.subscribed.clear();
    }

    /**
     * Holds a subscription for exactly the rows in [first]..[last] -- the window plus whatever
     * overscan the caller asked for. Rows that left it are released; the repository keeps them a
     * while longer, so scrolling back does not go to the server again.
     */
    private subscribeRange(first: number, last: number) {
        const wanted = new Set<string>();
        for (let index = first; index <= last; index++) {
            const id = this.entries[index];
            if (id !== undefined) wanted.add(id);
        }

        for (const [id, release] of this.subscribed) {
            if (wanted.has(id)) continue;
            release();
            this.subscribed.delete(id);
        }

        for (const id of wanted) {
            if (this.subscribed.has(id)) continue;
            this.subscribed.set(id, this.mails.subscribe(id));
        }
    }

    /**
     * The stretches, once. They are the shape of the list, not its contents -- a mail arriving
     * changes them, and asking again is what a reload or [retry] is for.
     */
    private async loadGroups() {
        if (this.groupCounts !== null || this.isLoadingGroups) return;
        this.isLoadingGroups = true;

        try {
            const response = await fetch(`/api/emails/list/groups?by=${this.grouping}`);
            if (!response.ok) throw new Error(`Could not read the mailbox shape: ${response.status}`);

            const answer = (await response.json()) as {groups?: MailGroupCount[]};
            // Checked rather than trusted: everything downstream lays out rows from this, and a
            // missing array would come out as an empty mailbox rather than as a failure.
            if (!Array.isArray(answer.groups)) throw new Error("The mailbox shape has no stretches");

            this.groupCounts = answer.groups;
        } catch (error) {
            console.error(error);
            // Left unset: the list stays flat, which is a listing without headers rather than no
            // listing at all.
            this.isLoadingGroups = false;
        }
    }

    private async load(offset: number) {
        if (this.pages.has(offset)) return;
        this.pages.add(offset);

        try {
            const response = await fetch(`/api/emails/list?offset=${offset}&limit=${PAGE_SIZE}`);
            if (!response.ok) throw new Error(`Could not read the mailbox: ${response.status}`);

            const page = (await response.json()) as {total: number; offset: number; ids: string[]};

            this.total = page.total;
            // Placed by the offset the answer carries, not the one that was asked for: two pages
            // in flight can come back in either order.
            page.ids.forEach((id, index) => (this.entries[page.offset + index] = id));
            this.initialized = true;
            this.failed = false;
        } catch (error) {
            console.error(error);
            // Forgotten, so a scroll or the retry button asks for it again.
            this.pages.delete(offset);
            this.failed = true;
        }
    }
}
