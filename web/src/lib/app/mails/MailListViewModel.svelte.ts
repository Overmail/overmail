import {SvelteMap} from "svelte/reactivity";
import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";
import type {ViewFilter} from "$lib/repository/ViewSocket";
import {filterKey, filterParams} from "$lib/app/mails/mailFilterQuery";
import {buildLayout, MailLayout, type MailGroupCount, type MailGroupNode} from "$lib/app/mails/mailLayout";
import {mailboxView, type ViewSettings} from "$lib/app/views/viewSettings";

/** How many mails one request asks for. The server caps this at 500. */
const PAGE_SIZE = 100;

/** One mail along the listing: -1 is up the table, which is the row before it. */
export type MailStep = -1 | 1;

/** Where a mail sits: the group the api knows it under, and its place in that group. */
export type MailPosition = {
    path: string[];
    offset: number;
};

/** Everything held for one view. Changing the view leaves what another one read alone. */
type Listing = {
    /** The groups as the server counted them, or null while the shape is on its way. */
    groups: MailGroupCount[] | null;

    /** The mails of each group by offset: a missing offset is one nobody has asked for. */
    entries: Record<string, Record<number, string>>;

    /** Whether anything has come back for this view yet. */
    initialized: boolean;

    /**
     * Bumped every time the mailbox moved -- see [MailListViewModel.refresh]. What was read
     * before that was read of another listing, so an answer from an older one is dropped rather
     * than filed at a place it no longer holds.
     */
    generation: number;

    /** Which generation the groups were read at. Behind [generation] means: read them again. */
    groupsGeneration: number;
};

const emptyListing = (): Listing => ({
    groups: null,
    entries: {},
    initialized: false,
    generation: 0,
    groupsGeneration: 0,
});

/**
 * What a view that has not been read yet looks like. Shared and never written to -- see the note
 * on [MailListViewModel.listing].
 */
const NOTHING_READ: Listing = emptyListing();

/**
 * A group as one string: what a page of it is filed under.
 *
 * A slash is enough of a separator: a key is a date, a number, an id or a state name, and none of
 * those carries one.
 */
const pathKey = (path: string[]) => path.join("/");

/**
 * The listing by position: which mail sits where, and where the headers between them go.
 *
 * Only positions and ids. What a row shows comes from the email repository, which this keeps
 * subscribed for the rows near the viewport and lets go of behind it -- the repository's grace
 * period is what makes that survive a flick of the scroll wheel.
 *
 * Two answers make a listing. The groups say what shape it has -- how deep it is cut and how long
 * every stretch is -- which is what lets a windowed table lay itself out before a single mail is
 * loaded. The pages say which mail sits at which offset of a group. A hole is a mail that exists
 * and has not been asked for; scrolling onto one is what asks.
 *
 * Mails are addressed by group and offset, never by a position in the whole listing: that is what
 * the api pages by, it survives a group above growing, and it still means something when the
 * listing is not ordered by date at all.
 *
 * Every view this has read keeps what it read, so going back to one shows what was already there.
 * The row subscriptions are shared: what a mail *is* does not depend on the listing it is in.
 */
export class MailListViewModel {
    /**
     * What was read, per view -- see [viewKey] for what makes two of them the same.
     *
     * A map rather than an object, and the listings in it are replaced rather than written to: a
     * reader asks for a view nobody has read yet, and what it has to hear about is exactly that
     * key appearing. A plain object of state answers a missing key without remembering that
     * anybody asked, and the table then sits at the length it read before the answer came.
     */
    private readonly listings = new SvelteMap<string, Listing>();

    /** What the listing is: what it leaves out, how it is cut up, what orders it. */
    private view: ViewSettings = $state(mailboxView());

    failed = $state(false);

    /** Requests on their way, by what they ask for, so nothing is asked for twice. */
    private readonly inFlight = new Set<string>();

    /**
     * The ids of a whole group as the server named them, by view, generation and group -- so
     * ticking one and taking it back again is one request, not two. Promises rather than
     * answers: a second click while the first is on its way waits for the same one.
     */
    private readonly stretches = new Map<string, Promise<string[]>>();

    /** The rows this holds a subscription for, by id. Kept across a change of view. */
    private readonly subscribed = new Map<string, () => void>();

    /** The last range a table asked for, so a move can read exactly that one again. */
    private lastWindow: {fromRow: number; toRow: number} | null = null;

    constructor(private readonly mails: EmailRepository) {
        // The listing of the view this starts on, put in here: everything that reads it goes
        // through a derived, and a derived is not allowed to be the one that writes it into
        // existence -- see [listing].
        this.update(this.key, {});
    }

    /** What identifies the listing being shown: everything held is keyed by it. */
    private get key(): string {
        return viewKey(this.view);
    }

    /**
     * What has been read under the current view, and an empty listing until anything has been.
     *
     * Reads only: a getter that started a listing would be writing state from inside whatever
     * derived asked for it, which Svelte refuses -- and rightly, the reader of a list is not what
     * decides that it exists. [listingFor] is the writing side.
     */
    private get listing(): Listing {
        return this.listings.get(this.key) ?? NOTHING_READ;
    }


    /** What has been read under [key], and an empty listing when that is nothing. */
    private listingFor(key: string): Listing {
        return this.listings.get(key) ?? NOTHING_READ;
    }

    /**
     * The one way a listing changes: what was held is replaced by what it is now.
     *
     * Replaced rather than written to, so the map is what says something happened -- see the note
     * on [listings]. Everything that reads a listing reads it through the map, so nothing is left
     * holding the old one.
     */
    private update(key: string, change: Partial<Listing>) {
        this.listings.set(key, {...(this.listings.get(key) ?? emptyListing()), ...change});
    }

    /**
     * Where every row sits: the headers, and which mail of which group the others hold.
     *
     * Read through the map on purpose: that is what tells whoever asks about a listing that was
     * not there yet when they first asked.
     */
    get layout(): MailLayout {
        const groups = this.listings.get(this.key)?.groups ?? null;
        if (groups === null) return MailLayout.flat(0);

        return buildLayout(groups, this.view.groupings);
    }

    /** How many mails the listing holds, headers not counted. */
    get total(): number {
        return this.layout.mailCount;
    }

    /** Whether anything came back for this listing yet. Before that a table stands in for it. */
    get initialized(): boolean {
        return this.listing.initialized;
    }

    /** The mail at [position], or undefined while that page is not here. */
    idAt(position: MailPosition): string | undefined {
        return this.listing.entries[pathKey(position.path)]?.[position.offset];
    }

    /**
     * The mails under [node] that this can name right now -- what a header counts its ticks
     * against while the rows under it are still coming in.
     */
    idsUnder(node: MailGroupNode): string[] {
        const ids: string[] = [];
        const entries = this.listing.entries;

        const walk = (current: MailGroupNode) => {
            if (current.children.length === 0) {
                const page = entries[pathKey(current.path)] ?? {};
                for (let offset = 0; offset < current.count; offset++) {
                    const id = page[offset];
                    if (id !== undefined) ids.push(id);
                }
                return;
            }

            for (const child of current.children) walk(child);
        };

        walk(node);
        return ids;
    }

    /**
     * Every mail under [node], asked of the server rather than read off what is here.
     *
     * What ticking a header means: the mails of a stretch, whether or not their rows were ever
     * drawn -- and a header of an outer level is every group under it, which is one request as
     * well. Held while it is on its way, and thrown away when the listing moves.
     */
    async idsOfGroup(node: MailGroupNode): Promise<string[]> {
        const key = this.key;
        const listing = this.listingFor(key);
        const stretchKey = key + ":" + listing.generation + ":" + pathKey(node.path);

        const held = this.stretches.get(stretchKey);
        if (held !== undefined) return held;

        const query = this.query();
        if (node.path.length > 0) query.set("group", node.path.join(","));

        const request = (async () => {
            const response = await fetch("/api/emails/list/ids?" + query);
            if (!response.ok) throw new Error("Could not read the stretch: " + response.status);

            const answer = (await response.json()) as {ids: string[]};
            if (!Array.isArray(answer.ids)) throw new Error("The stretch has no ids");

            this.failed = false;
            return answer.ids;
        })().catch((error) => {
            console.error(error);
            this.failed = true;
            this.stretches.delete(stretchKey);

            // What the listing holds of it, so the click does something rather than nothing. The
            // failure itself is on screen: it is the same bar the pages report through.
            return this.idsUnder(node);
        });

        this.stretches.set(stretchKey, request);
        return request;
    }

    /** Where [id] sits, or undefined for a mail this listing does not hold. */
    positionOf(id: string): MailPosition | undefined {
        for (const [key, page] of Object.entries(this.listing.entries)) {
            for (const [offset, held] of Object.entries(page)) {
                if (held !== id) continue;

                return {path: key === "" ? [] : key.split("/"), offset: Number(offset)};
            }
        }

        return undefined;
    }

    /** Which row [id] is drawn in, or undefined while it is not in what was read. */
    rowOf(id: string): number | undefined {
        const position = this.positionOf(id);
        if (position === undefined) return undefined;

        const layout = this.layout;
        const key = pathKey(position.path);

        for (let row = 0; row < layout.length; row++) {
            const entry = layout.rowAt(row);
            if (entry?.kind !== "mail" || pathKey(entry.path) !== key) continue;

            return row + (position.offset - entry.offset);
        }

        return undefined;
    }

    /** The mail one step from [id] along the listing, or undefined at either end. */
    step(id: string, step: MailStep): string | undefined {
        const row = this.rowOf(id);
        if (row === undefined) return undefined;

        const layout = this.layout;
        for (let next = row + step; next >= 0 && next < layout.length; next += step) {
            const entry = layout.rowAt(next);
            if (entry?.kind !== "mail") continue;

            return this.idAt(entry);
        }

        return undefined;
    }

    /** Whether there is a mail one step from [id] at all. */
    canStep(id: string | null, step: MailStep): boolean {
        if (id === null) return false;

        const row = this.rowOf(id);
        if (row === undefined) return false;

        const layout = this.layout;
        for (let next = row + step; next >= 0 && next < layout.length; next += step) {
            if (layout.rowAt(next)?.kind === "mail") return true;
        }

        return false;
    }

    /**
     * Switches what the list is about. Nothing is thrown away: what another view read stays where
     * it was, and the rows on screen keep their subscriptions -- what a mail is does not depend
     * on the listing it is shown in.
     *
     * A view that asks for the same listing is not a change, whatever object it arrives in: the
     * caller builds a fresh one whenever anything around it moves, and every one of those would
     * otherwise be a listing read again from the top.
     */
    setView(view: ViewSettings) {
        if (viewKey(view) === this.key) return;

        this.view = view;
        // Started here rather than left to whoever reads it: a listing nobody has read yet is
        // empty, and an empty one asks for nothing, so the table would sit at a length of zero
        // waiting for a scroll that never comes.
        this.update(this.key, {});

        const last = this.lastWindow;
        if (last !== null) this.window(last.fromRow, last.toRow);
    }

    /**
     * Makes sure the rows between [fromRow] and [toRow] -- rows of the layout, headers included
     * -- are on their way and stay up to date. Cheap to call on every scroll frame: it asks for
     * what is missing and nothing else.
     */
    window(fromRow: number, toRow: number) {
        this.lastWindow = {fromRow, toRow};
        void this.loadGroups();

        const wanted = this.layout.mailsIn(fromRow, toRow - fromRow + 1);

        for (const position of wanted) {
            if (this.idAt(position) === undefined) void this.load(position);
        }

        this.subscribeRange(wanted);
    }

    /**
     * Reads everything again, keeping what is on screen where it is.
     *
     * A mail arriving or being filed moves the listing under the reader: the counts change, and
     * with them which mail sits at which offset. Rather than shifting rows about, the shape and
     * the pages around the window are read again and replace what was there in one go.
     */
    refresh() {
        // A group is a set of positions, and a move is what makes them mean other mails.
        this.stretches.clear();

        for (const [key, listing] of this.listings) {
            this.listings.set(key, {...listing, generation: listing.generation + 1});
        }

        const last = this.lastWindow;
        if (last !== null) this.window(last.fromRow, last.toRow);
    }

    /** Asks again after a failure -- what the retry button does. */
    retry() {
        this.failed = false;
        this.listings.set(this.key, emptyListing());
        this.inFlight.clear();
        this.stretches.clear();

        const last = this.lastWindow;
        this.window(last?.fromRow ?? 0, last?.toRow ?? 0);
    }

    /** Lets go of every row. The table calls this when it goes away. */
    dispose() {
        this.subscribed.forEach((release) => release());
        this.subscribed.clear();
    }

    /**
     * Holds a subscription for exactly the mails in [wanted]. Rows that left the window are
     * released; the repository keeps them a while longer, so scrolling back -- or switching the
     * view and back -- does not go to the server again.
     */
    private subscribeRange(wanted: MailPosition[]) {
        const ids = new Set<string>();
        for (const position of wanted) {
            const id = this.idAt(position);
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

    /** The page [position] sits in, read and filed under its group. */
    private async load(position: MailPosition) {
        const key = this.key;
        const listing = this.listingFor(key);
        const generation = listing.generation;

        // Pages start at multiples of the page size, so two rows of the same page are one
        // request and a window that moves by one row does not shift every boundary.
        const start = Math.floor(position.offset / PAGE_SIZE) * PAGE_SIZE;
        const group = pathKey(position.path);

        // The generation is part of the key: a request that was cut for the listing as it stood
        // before a move must not stand in the way of the one that reads it again.
        const request = key + ":" + generation + ":" + group + ":" + start;
        if (this.inFlight.has(request)) return;
        this.inFlight.add(request);

        try {
            const query = this.query();
            if (position.path.length > 0) query.set("group", position.path.join(","));
            query.set("offset", String(start));
            query.set("limit", String(PAGE_SIZE));

            const response = await fetch("/api/emails/list?" + query);
            if (!response.ok) throw new Error("Could not read the mailbox: " + response.status);

            const answer = (await response.json()) as {ids: string[]};
            if (!Array.isArray(answer.ids)) throw new Error("The page has no ids");

            // The listing may have moved while this was out; then it is about mails that are no
            // longer at these offsets.
            const held = this.listingFor(key);
            if (held.generation !== generation) return;

            const page = {...(held.entries[group] ?? {})};
            answer.ids.forEach((id, index) => (page[start + index] = id));
            this.update(key, {
                entries: {...held.entries, [group]: page},
                initialized: true,
            });
            this.failed = false;

            // The rows that were waiting for this page are on screen now.
            const last = this.lastWindow;
            if (last !== null) {
                this.subscribeRange(this.layout.mailsIn(last.fromRow, last.toRow - last.fromRow + 1));
            }
        } catch (error) {
            console.error(error);
            this.failed = true;
        } finally {
            this.inFlight.delete(request);
        }
    }

    /** The shape of the listing: how deep it is cut and how long every stretch is. */
    private async loadGroups() {
        const key = this.key;
        const listing = this.listingFor(key);
        const generation = listing.generation;

        if (listing.groups !== null && listing.groupsGeneration === generation) return;

        const request = key + ":" + generation + ":groups";
        if (this.inFlight.has(request)) return;
        this.inFlight.add(request);

        try {
            const response = await fetch("/api/emails/list/groups?" + this.query());
            if (!response.ok) throw new Error("Could not read the mailbox shape: " + response.status);

            const answer = (await response.json()) as {groups: MailGroupCount[]};
            if (!Array.isArray(answer.groups)) throw new Error("The mailbox shape has no groups");
            if (this.listingFor(key).generation !== generation) return;

            this.update(key, {
                groups: answer.groups,
                groupsGeneration: generation,
                initialized: true,
            });
            this.failed = false;

            // The shape is what says which rows there are, so the window that was asked for
            // before it arrived is only now a range of mails.
            const last = this.lastWindow;
            if (last !== null) this.window(last.fromRow, last.toRow);
        } catch (error) {
            console.error(error);
            this.failed = true;
        } finally {
            this.inFlight.delete(request);
        }
    }

    /** What every request carries: the filter, the levels, and what orders the mails. */
    private query(): URLSearchParams {
        const query = filterParams(this.view.filter);

        const levels = this.view.groupings.map((grouping) => grouping.kind).join(",");
        if (levels !== "") query.set("by", levels);

        const sorting = this.view.sorting;
        query.set("sort", sorting.reversed ? sorting.kind + ":r" : sorting.kind);

        return query;
    }
}

/**
 * What makes two views the same listing: the mails in it, how it is cut, and what orders them.
 *
 * The name of a view and where it sits in the sidebar are not in it -- two views set up the same
 * way read the same listing, and holding it twice would be reading it twice.
 */
export function viewKey(view: ViewSettings): string {
    const levels = view.groupings.map((grouping) => grouping.kind + (grouping.reversed ? ":r" : ""));
    const sorting = view.sorting.kind + (view.sorting.reversed ? ":r" : "");

    return filterKey(view.filter) + "|" + levels.join(",") + "|" + sorting;
}

/** Everything a listing lets through, which is the filter nobody set. */
export const everyMail = (): ViewFilter => ({
    readState: null,
    archivedState: null,
    imapAccountIds: null,
    sentBy: null,
    sentTo: null,
    hasLabels: null,
});
