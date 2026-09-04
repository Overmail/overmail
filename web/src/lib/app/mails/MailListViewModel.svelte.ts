import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import {
    MailLayout,
    foldGroups,
    type MailGrouping,
    type MailGroupCount,
} from "$lib/app/mails/grouping";

/** How many mails one request asks for. The server caps this at 500. */
const PAGE_SIZE = 100;

/** Which mails a listing is about, as the server names them. */
export type MailScope = "unarchived" | "all";

/** Where a page carries on: a send time in whole seconds, and the mail that sat at it. */
type Cursor = {before: number; beforeId?: string};

/** Everything held for one scope. Switching scope leaves the other one's untouched. */
type Listing = {
    /** Row of this listing -> mail id. A missing row is one that has not been asked for. */
    entries: Record<number, string>;

    /**
     * Where a request has to carry on to get the row at that index. Row 0 needs none, and every
     * answer says where the page after it begins.
     */
    cursors: Record<number, Cursor>;

    /** How many mails this scope holds. 0 until the first page came back. */
    total: number;

    initialized: boolean;

    /** The days the server counted, or null while they are on their way. */
    groupCounts: MailGroupCount[] | null;
};

const emptyListing = (): Listing => ({
    entries: {},
    cursors: {},
    total: 0,
    initialized: false,
    groupCounts: null,
});

/** The days of a scope with the row each of them starts at. */
type Day = {key: string | null; count: number; start: number};

/**
 * The mailbox by position: which mail sits at which row, and where the headers between them go.
 *
 * Only positions and ids. What a row shows comes from the email repository, which this keeps
 * subscribed for the rows near the viewport and lets go of behind it -- the repository's grace
 * period is what makes that survive a flick of the scroll wheel.
 *
 * Two answers make a listing: the days say how long it is and where the headers sit, which is
 * what lets a windowed table lay itself out before a single mail is loaded, and the pages fill in
 * which mail sits where. A hole is a mail that exists and has not been asked for; scrolling onto
 * one is what asks.
 *
 * Pages are asked for by send time, never by position. A day the server counted is a cursor -- to
 * draw the rows of a day, ask for the mails older than the day above it -- and every answer says
 * where the page after it carries on. That is also what makes the scope switch cheap: a position
 * only means something within one scope, a send time means the same mail in both.
 *
 * Both scopes are held at once, so switching back shows what was already there. The row
 * subscriptions are shared: what a mail *is* does not depend on which listing it is shown in.
 */
export class MailListViewModel {
    private readonly listings: Record<MailScope, Listing> = $state({
        unarchived: emptyListing(),
        all: emptyListing(),
    });

    private scope: MailScope = $state("unarchived");

    failed = $state(false);

    /** Requests on their way, by the row they start at, so nothing is asked for twice. */
    private readonly inFlight = new Set<string>();

    /** The rows this holds a subscription for, by id. Kept across a scope switch. */
    private readonly subscribed = new Map<string, () => void>();

    constructor(
        private readonly mails: EmailRepository,
        private readonly grouping: MailGrouping = "date",
    ) {}

    private get listing(): Listing {
        return this.listings[this.scope];
    }

    /** How many mails the current scope holds. */
    get total(): number {
        return this.listing.total;
    }

    /** Whether anything came back for this scope yet. Before that a table stands in for it. */
    get initialized(): boolean {
        return this.listing.initialized;
    }

    /**
     * Where every row sits. Until the days are here it is the mailbox without headers, so a table
     * can already page and draw.
     */
    get layout(): MailLayout {
        const days = this.listing.groupCounts;
        if (days === null) return MailLayout.flat(this.listing.total);

        // Read as the layout is built rather than kept ticking: the days are re-read when the
        // list changes, and a table open at midnight is nobody's problem.
        return new MailLayout(foldGroups(days, new Date()));
    }

    /** The mail at [index] of the current scope, or undefined while that page is not here. */
    idAt(index: number): string | undefined {
        return this.listing.entries[index];
    }

    /**
     * Switches which mails the list is about. Nothing is thrown away: the other scope keeps its
     * pages and its days, and the rows on screen keep their subscriptions -- what a mail is does
     * not change with the scope it is listed in.
     */
    setScope(scope: MailScope) {
        this.scope = scope;
    }

    /**
     * Makes sure the rows between [fromRow] and [toRow] -- rows of the layout, headers included
     * -- are on their way and stay up to date. Cheap to call on every scroll frame: it asks for
     * what is missing and nothing else.
     */
    window(fromRow: number, toRow: number) {
        void this.loadDays();

        const layout = this.layout;
        const first = Math.max(0, Math.min(fromRow, toRow));
        const last = Math.max(fromRow, toRow);

        // Which mails those rows hold. The headers in between are the reason this is not the
        // range itself.
        const wanted: number[] = [];
        for (let row = first; row <= last; row++) {
            const entry = layout.rowAt(row);
            if (entry?.kind === "mail") wanted.push(entry.index);
        }

        if (wanted.length === 0) {
            // Nothing is known before the first page, and it is that page which reports the
            // length -- so ask for the top and the rest follows from the scrolling.
            if (!this.listing.initialized) void this.load(0);
            return;
        }

        const gap = wanted.find((index) => this.listing.entries[index] === undefined);
        if (gap !== undefined) void this.load(gap);

        this.subscribeRange(wanted);
    }

    /** Asks again after a failure -- what the retry button does. */
    retry() {
        this.failed = false;
        this.listings[this.scope] = emptyListing();
        this.inFlight.clear();
        void this.loadDays();
        void this.load(0);
    }

    /** Lets go of every row. The table calls this when it goes away. */
    dispose() {
        this.subscribed.forEach((release) => release());
        this.subscribed.clear();
    }

    /**
     * Holds a subscription for exactly the mails in [wanted]. Rows that left the window are
     * released; the repository keeps them a while longer, so scrolling back -- or switching
     * scope and back -- does not go to the server again.
     */
    private subscribeRange(wanted: number[]) {
        const ids = new Set<string>();
        for (const index of wanted) {
            const id = this.listing.entries[index];
            if (id !== undefined) ids.add(id);
        }

        for (const [id, release] of this.subscribed) {
            if (ids.has(id)) continue;
            release();
            this.subscribed.delete(id);
        }

        for (const id of ids) {
            if (this.subscribed.has(id)) continue;
            this.subscribed.set(id, this.mails.subscribe(id));
        }
    }

    /** The days of this scope with the row each of them starts at, newest first. */
    private days(): Day[] {
        const counts = this.listing.groupCounts;
        if (counts === null) return [];

        let start = 0;
        return counts.map((day) => {
            const entry = {key: day.key, count: day.count, start};
            start += day.count;
            return entry;
        });
    }

    /**
     * Where a request for the row at [index] has to carry on.
     *
     * The nearest cursor at or before it, which after a page is the row that page ended on, and
     * at the start of a day is that day's boundary -- so a jump into the middle of the list is
     * one request rather than a walk down from the top.
     */
    private cursorFor(index: number): {row: number; cursor: Cursor | null} | null {
        const day = this.days().find((entry) => index < entry.start + entry.count);

        // The first row of the list needs no cursor at all.
        const floor = day?.start ?? 0;
        for (let row = index; row > floor; row--) {
            const cursor = this.listing.cursors[row];
            if (cursor !== undefined) return {row, cursor};
        }

        if (floor === 0) return {row: 0, cursor: this.listing.cursors[0] ?? null};
        if (day?.key == null) return null;

        // A day is a cursor: everything older than the day above it starts with this day's
        // newest mail, which is the row the day starts at.
        return {row: floor, cursor: {before: startOfDayAfter(day.key)}};
    }

    private async load(index: number) {
        const anchor = this.cursorFor(index);
        if (anchor === null) return;

        const key = `${this.scope}:${anchor.row}`;
        if (this.inFlight.has(key)) return;
        this.inFlight.add(key);

        const scope = this.scope;

        try {
            const query = new URLSearchParams({scope, limit: String(PAGE_SIZE)});
            if (anchor.cursor !== null) {
                query.set("before", String(anchor.cursor.before));
                if (anchor.cursor.beforeId !== undefined) {
                    query.set("before_id", anchor.cursor.beforeId);
                }
            }

            const response = await fetch(`/api/emails/list?${query}`);
            if (!response.ok) throw new Error(`Could not read the mailbox: ${response.status}`);

            const page = (await response.json()) as {
                total: number;
                ids: string[];
                next: {before: number; before_id: string} | null;
            };

            const listing = this.listings[scope];
            listing.total = page.total;
            page.ids.forEach((id, offset) => (listing.entries[anchor.row + offset] = id));
            if (page.next !== null) {
                listing.cursors[anchor.row + page.ids.length] = {
                    before: page.next.before,
                    beforeId: page.next.before_id,
                };
            }
            listing.initialized = true;
            this.failed = false;
        } catch (error) {
            console.error(error);
            this.failed = true;
        } finally {
            // Forgotten either way: a page that failed is asked for again by the next scroll, and
            // one that arrived is not asked for again because its rows are filled.
            this.inFlight.delete(key);
        }
    }

    /**
     * The days of this scope, once. They are the shape of the list, not its contents -- a mail
     * arriving changes them, and asking again is what a reload or [retry] is for.
     */
    private async loadDays() {
        const scope = this.scope;
        if (this.listings[scope].groupCounts !== null) return;

        const key = `${scope}:days`;
        if (this.inFlight.has(key)) return;
        this.inFlight.add(key);

        try {
            const response = await fetch(`/api/emails/list/groups?by=${this.grouping}&scope=${scope}`);
            if (!response.ok) throw new Error(`Could not read the mailbox shape: ${response.status}`);

            const answer = (await response.json()) as {groups?: MailGroupCount[]};
            // Checked rather than trusted: everything downstream lays out rows from this, and a
            // missing array would come out as an empty mailbox rather than as a failure.
            if (!Array.isArray(answer.groups)) throw new Error("The mailbox shape has no stretches");

            this.listings[scope].groupCounts = answer.groups;
        } catch (error) {
            console.error(error);
            // Left unset: the list stays flat, which is a listing without headers rather than no
            // listing at all.
            this.inFlight.delete(key);
        }
    }
}

/**
 * Midnight after the day [key] -- `yyyy-mm-dd` -- in epoch seconds.
 *
 * Local time, because that is the zone the server cut the days in. Everything older than this is
 * everything from that day down, which is exactly where that day's stretch starts.
 */
function startOfDayAfter(key: string): number {
    const [year, month, day] = key.split("-").map(Number);
    return new Date(year, month - 1, day + 1).getTime() / 1000;
}
