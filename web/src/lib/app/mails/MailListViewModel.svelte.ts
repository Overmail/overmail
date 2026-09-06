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

/** One mail along the listing: -1 is up the table, which is the newer one. */
export type MailStep = -1 | 1;

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

    /**
     * Bumped every time the mailbox moved -- see [MailListViewModel.refresh]. What was read
     * before that was read of another listing, so an answer from an older one is dropped rather
     * than filed at a position it no longer holds.
     */
    generation: number;

    /** Which generation [entries] were read at. Behind [generation] means: read them again. */
    entriesGeneration: number;

    /** The same for [groupCounts], which are re-read on their own once per move. */
    daysGeneration: number;
};

const emptyListing = (): Listing => ({
    entries: {},
    cursors: {},
    total: 0,
    initialized: false,
    groupCounts: null,
    generation: 0,
    entriesGeneration: 0,
    daysGeneration: 0,
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

    /**
     * The ids of a stretch as the server named them, by scope, generation and stretch -- so
     * ticking one and taking it back again is one request, not two. Promises rather than
     * answers: a second click while the first is on its way waits for the same one.
     */
    private readonly stretches = new Map<string, Promise<string[]>>();

    /** The rows this holds a subscription for, by id. Kept across a scope switch. */
    private readonly subscribed = new Map<string, () => void>();

    /** The last range a table asked for, so a move can read exactly that one again. */
    private lastWindow: {fromRow: number; toRow: number} | null = null;

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
     * The mails of a stretch of the current scope that this can name: [count] positions from
     * [from] on, and of those the ones whose page is here.
     *
     * Which is less than the stretch holds for as long as it has not been paged in -- what the
     * table shows there are the rows it is keeping open. A caller that ticks these ticks the
     * mails the list holds, not the ones nobody has asked for yet.
     */
    idsIn(from: number, count: number): string[] {
        const ids: string[] = [];

        for (let index = from; index < from + count; index++) {
            const id = this.listing.entries[index];
            if (id !== undefined) ids.push(id);
        }

        return ids;
    }

    /**
     * Every mail of a stretch -- [count] positions from [from] -- including the ones no page of
     * this listing has been asked for.
     *
     * Which is what picking a whole stretch needs, and why it is one request rather than a walk
     * down its pages: the table holds what somebody scrolled through, and ticking only that would
     * quietly leave the rest of the day out of whatever is done with the selection next.
     *
     * The stretch is turned into the send times it spans -- the day boundaries the server counted
     * its days by -- because that is what the api pages and cuts by; see `GET
     * /api/emails/list/ids`. A listing whose days are not here yet has no stretches to ask about,
     * and answers with what it holds.
     */
    async idsOfStretch(from: number, count: number): Promise<string[]> {
        const scope = this.scope;
        const listing = this.listings[scope];
        const generation = listing.generation;

        // Newest first, like everything else here, so the first of them ends the range and the
        // last one starts it.
        const days = this.days().filter(
            (day) => day.key !== null && day.start < from + count && day.start + day.count > from
        );

        const newest = days[0]?.key;
        const oldest = days.at(-1)?.key;
        if (newest == null || oldest == null) return this.idsIn(from, count);

        const key = `${scope}:${generation}:${oldest}:${newest}`;
        const held = this.stretches.get(key);
        if (held !== undefined) return held;

        const query = new URLSearchParams({
            scope,
            from: String(startOfDay(oldest)),
            to: String(startOfDayAfter(newest)),
        });

        const request = (async () => {
            const response = await fetch(`/api/emails/list/ids?${query}`);
            if (!response.ok) throw new Error(`Could not read the stretch: ${response.status}`);

            const answer = (await response.json()) as {ids: string[]};
            if (!Array.isArray(answer.ids)) throw new Error("The stretch has no ids");

            this.failed = false;
            return answer.ids;
        })().catch((error) => {
            console.error(error);
            this.failed = true;
            this.stretches.delete(key);

            // What the listing holds of it, so the click does something rather than nothing. The
            // failure itself is on screen: it is the same bar the pages report through.
            const entries = listing.entries;
            const known: string[] = [];
            for (let index = from; index < from + count; index++) {
                const id = entries[index];
                if (id !== undefined) known.push(id);
            }

            return known;
        });

        this.stretches.set(key, request);
        return request;
    }

    /**
     * Where [id] sits in the current scope. Undefined for a mail this listing does not hold: one
     * of another scope, or one whose page has been let go of.
     *
     * A scan over what is loaded rather than a reverse index: what is held are the pages that
     * have been scrolled to, and this is asked when the open mail changes, not per frame.
     */
    indexOf(id: string): number | undefined {
        for (const [index, entry] of Object.entries(this.listing.entries)) {
            if (entry === id) return Number(index);
        }

        return undefined;
    }

    /**
     * The mail one step from [id], as the table has them ordered -- up is the row above.
     *
     * Undefined at either end of the listing, and undefined while the page that mail sits on is
     * not here; asking for that page is what this does in that case, so the same call once it
     * landed answers. Only the ends are worth telling a user about, which is [canStep].
     */
    step(id: string, step: MailStep): string | undefined {
        const index = this.indexOf(id);
        if (index === undefined) return undefined;

        const next = index + step;
        if (!this.holds(next)) return undefined;

        const nextId = this.listing.entries[next];
        if (nextId === undefined) void this.load(next);

        return nextId;
    }

    /** Whether a step leads anywhere at all -- what greys a button out rather than doing nothing. */
    canStep(id: string, step: MailStep): boolean {
        const index = this.indexOf(id);
        return index !== undefined && this.holds(index + step);
    }

    private holds(index: number): boolean {
        return index >= 0 && index < this.total;
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
        this.lastWindow = {fromRow, toRow};
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

        // Rows from before a move are asked for again even though they are filled: they are
        // positions, and the answer is what replaces the lot of them (see [load]).
        const listing = this.listing;
        const stale = listing.entriesGeneration !== listing.generation;
        const gap = wanted.find((index) => listing.entries[index] === undefined);

        if (stale) void this.load(wanted[0]);
        else if (gap !== undefined) void this.load(gap);

        this.subscribeRange(wanted);
    }

    /**
     * Says that the mailbox moved: a mail arrived, or one left a listing by being archived or
     * filed. What this holds are positions, and every one of them may mean another mail now.
     *
     * The shape stays up while it is re-read. The length and the days are what a windowed table
     * lays itself out from, and dropping them would collapse a listing somebody is scrolled deep
     * into to eight ghost rows and throw the scroll position away with it. What goes right away
     * are the cursors: they say where a position carries on, so after a move they point one mail
     * beside it -- the rows are read from the day boundaries again instead, and the first page
     * back replaces every row in one go rather than one flashing skeleton at a time.
     *
     * Both scopes, because a mail arriving is in both. The one that is not on screen is read
     * again the next time it is, rather than shown as it was before the move.
     */
    refresh() {
        // A stretch is a set of positions, and a move is what makes them mean other mails.
        this.stretches.clear();

        for (const listing of Object.values(this.listings)) {
            listing.generation++;
            listing.cursors = {};
        }

        // Nothing has been asked for yet: whatever a table asks for first reads the new listing
        // anyway.
        const last = this.lastWindow;
        if (last !== null) this.window(last.fromRow, last.toRow);
    }

    /** Asks again after a failure -- what the retry button does. */
    retry() {
        this.failed = false;
        this.listings[this.scope] = emptyListing();
        this.inFlight.clear();
        this.stretches.clear();
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

        const scope = this.scope;
        const listing = this.listings[scope];
        const generation = listing.generation;

        // The generation is part of the key: a request that was cut for the listing as it stood
        // before a move must not stand in the way of the one that reads it again.
        const key = `${scope}:${generation}:${anchor.row}`;
        if (this.inFlight.has(key)) return;
        this.inFlight.add(key);

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

            // Cut for a listing that has moved on since: what came back says which mail sits at
            // which position, and that is what the move invalidated.
            if (listing.generation !== generation) return;

            // The first page of a new generation replaces what is here rather than being filed
            // into it: the rows it does not cover would otherwise stay as they were read before
            // the move, with nothing left to correct them.
            if (listing.entriesGeneration !== generation) {
                listing.entries = {};
                listing.cursors = {};
                listing.entriesGeneration = generation;
            }

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
     * The days of this scope: how long the listing is and where its headers sit.
     *
     * Once per generation. They are the shape of the list, not its contents, so they are read
     * again when the mailbox moved -- and swapped for the new ones only when those are here, so
     * a table keeps its headers and its scroll position while they are on their way.
     */
    private async loadDays() {
        const scope = this.scope;
        const listing = this.listings[scope];
        const generation = listing.generation;
        if (listing.groupCounts !== null && listing.daysGeneration === generation) return;

        const key = `${scope}:${generation}:days`;
        if (this.inFlight.has(key)) return;
        this.inFlight.add(key);

        try {
            const response = await fetch(`/api/emails/list/groups?by=${this.grouping}&scope=${scope}`);
            if (!response.ok) throw new Error(`Could not read the mailbox shape: ${response.status}`);

            const answer = (await response.json()) as {groups?: MailGroupCount[]};
            // Checked rather than trusted: everything downstream lays out rows from this, and a
            // missing array would come out as an empty mailbox rather than as a failure.
            if (!Array.isArray(answer.groups)) throw new Error("The mailbox shape has no stretches");

            if (listing.generation !== generation) return;

            listing.groupCounts = answer.groups;
            listing.daysGeneration = generation;
        } catch (error) {
            console.error(error);
            // Left unset, and the key stays: the list carries on flat -- a listing without
            // headers rather than no listing at all -- and this is not asked for again until
            // something says it is worth another try. A table asks for its window on every
            // scroll frame, so a retry here would be a request per frame for as long as the
            // days cannot be read. [retry] clears the keys, and a move is a new generation.
        }
    }
}

/**
 * Midnight of the day [key] -- `yyyy-mm-dd` -- in epoch seconds, and the same local zone as
 * [startOfDayAfter]. Where a stretch begins: the oldest mail of its oldest day was sent at or
 * after this.
 */
function startOfDay(key: string): number {
    const [year, month, day] = key.split("-").map(Number);
    return new Date(year, month - 1, day).getTime() / 1000;
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
