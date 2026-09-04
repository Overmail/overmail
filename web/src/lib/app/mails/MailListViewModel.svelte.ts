import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";

/** How many mails one request asks for. The server caps this at 500. */
const PAGE_SIZE = 100;

/**
 * The mailbox by position: which mail sits at which row.
 *
 * Only positions and ids. What a row shows comes from the email repository, which this keeps
 * subscribed for the rows near the viewport and lets go of behind it -- the repository's grace
 * period is what makes that survive a flick of the scroll wheel.
 *
 * The list is as long as the mailbox as soon as the first page came back, so a windowed table can
 * be the right length before it knows what is in it. A hole in it is a mail that exists and has
 * not been asked for; scrolling onto one is what asks.
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

    constructor(private readonly mails: EmailRepository) {}

    /** The mail at [index], or undefined while that page is not here. */
    idAt(index: number): string | undefined {
        return this.entries[index];
    }

    /**
     * Makes sure the rows between [from] and [to] are on their way and stay up to date. Cheap to
     * call on every scroll frame: it asks for the pages that are missing and nothing else.
     */
    window(from: number, to: number) {
        const first = Math.max(0, Math.min(from, to));
        const last = Math.max(from, to);

        // Nothing is known before the first page, and it is that page which reports the length.
        if (!this.initialized) {
            void this.load(0);
            return;
        }

        const end = Math.min(last, this.total - 1);
        if (first > end) return;

        for (let offset = Math.floor(first / PAGE_SIZE) * PAGE_SIZE; offset <= end; offset += PAGE_SIZE) {
            void this.load(offset);
        }

        this.subscribeRange(first, end);
    }

    /** Asks again after a failure -- what the retry button does. */
    retry() {
        this.failed = false;
        this.pages.clear();
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
