import type {ViewArchivedState, ViewGrouping, ViewGroupingKind} from "$lib/repository/ViewSocket";

/** One group as the server counted it: one key per level, outermost first. */
export type MailGroupCount = {
    keys: string[];
    count: number;
};

/** The four stretches a smart date listing names rather than dates; see `SmartDateBucket`. */
const SMART_TODAY = 1;
const SMART_MONTH = 4;

/**
 * What a header stands for. Not what it *says*: a correspondent is a name this does not have and
 * a date is a wording, so both are looked up where the header is drawn.
 */
export type MailGroupLabel =
    | {kind: "today"}
    | {kind: "yesterday"}
    | {kind: "week"}
    | {kind: "month"}
    /** [month] is 1-12. */
    | {kind: "calendarMonth"; year: number; month: number}
    | {kind: "year"; year: number}
    | {kind: "day"; date: string}
    | {kind: "sender"; id: string}
    | {kind: "account"; id: string}
    | {kind: "read"; isRead: boolean}
    | {kind: "archived"; state: ViewArchivedState};

/** One group of the listing, with the groups under it. */
export type MailGroupNode = {
    /** The keys from the outermost level down to this one -- what the api calls this group. */
    path: string[];
    /** 0 is the outermost level. */
    level: number;
    /** Null for a stretch with no header over it, which is what an ungrouped listing is. */
    label: MailGroupLabel | null;
    /** How much mail is under it, its children included. */
    count: number;
    /** Empty at the deepest level, which is where the mails themselves sit. */
    children: MailGroupNode[];
};

/** One row of the table: a header of some level, or one mail of a group. */
export type MailLayoutRow =
    | {kind: "header"; node: MailGroupNode}
    /** The mail at [offset] of the group [path], which is what the api is asked for. */
    | {kind: "mail"; path: string[]; offset: number};

/** What a leaf group contributes to the table: the headers that open above it, then its mails. */
type Block = {
    /** Where the block starts in the layout, its headers included. */
    start: number;
    /** The headers opening here, outermost first: the levels this leaf is the first group of. */
    headers: MailGroupNode[];
    /**
     * Every header this stretch is under, outermost first -- the ones opening above it included.
     *
     * What [headers] leaves out: a second correspondent of the same day belongs to that day just
     * as much as the first one does, it only does not open it. This is what a pinned header reads.
     */
    chain: MailGroupNode[];
    leaf: MailGroupNode;
    /** Where the leaf's first mail sits among all the mails of the listing. */
    mailStart: number;
};

/**
 * Where every row of the table sits: which are headers of which level, and which mail of which
 * group the others hold.
 *
 * Built from the counts alone -- the shape of the listing arrives long before any mail does, and
 * a windowed table needs the shape to lay itself out. Nothing is materialised per row: what is
 * held is one block per group at the deepest level, and a row is found in it by halving.
 *
 * A mail is addressed by its group and its offset in it rather than by a position in the whole
 * listing: that is what the api pages by, and it is the only thing that still means the same mail
 * when a group above it grows.
 */
export class MailLayout {
    /** The groups of the outermost level, in the order they are shown. */
    readonly roots: MailGroupNode[];

    private readonly blocks: Block[] = [];

    /** Where each header is drawn, by the path of its group; see [headerRow]. */
    private readonly headerRows = new Map<string, number>();

    /** How many rows the table has, headers included. */
    readonly length: number;

    /** How many of those rows are mails. */
    readonly mailCount: number;

    /** How deep the listing is cut. 0 is a listing without headers. */
    readonly depth: number;

    constructor(roots: MailGroupNode[], depth: number) {
        this.roots = roots;
        this.depth = depth;

        let row = 0;
        let mail = 0;

        const walk = (node: MailGroupNode, opening: MailGroupNode[], over: MailGroupNode[]) => {
            const headers = node.label === null ? [...opening] : [...opening, node];
            const chain = node.label === null ? over : [...over, node];

            if (node.children.length === 0) {
                this.blocks.push({start: row, headers, chain, leaf: node, mailStart: mail});
                headers.forEach((header, index) => this.headerRows.set(pathKey(header.path), row + index));
                row += headers.length + node.count;
                mail += node.count;
                return;
            }

            // The headers of this node open above the first of its children and nowhere else.
            let first = true;
            for (const child of node.children) {
                walk(child, first ? headers : [], chain);
                first = false;
            }
        };

        for (const root of roots) walk(root, [], []);

        this.length = row;
        this.mailCount = mail;
    }

    /** A listing without headers, which is what stands in until the groups are known. */
    static flat(mails: number): MailLayout {
        if (mails === 0) return new MailLayout([], 0);

        // No label, so no header: the one stretch of an ungrouped listing is the mails alone.
        return new MailLayout([{path: [], level: 0, label: null, count: mails, children: []}], 0);
    }

    /**
     * The headers the row at [index] sits under, outermost first.
     *
     * Not the ones drawn there -- the ones it *belongs* to, which is what stays on screen while
     * the stretch is scrolled through. Empty for a listing without headers.
     */
    headersAt(index: number): MailGroupNode[] {
        if (index < 0 || index >= this.length) return [];

        return this.blockAt(index)?.chain ?? [];
    }

    /** Which row draws the header of [node]; every group has exactly one. */
    headerRow(node: MailGroupNode): number | undefined {
        return this.headerRows.get(pathKey(node.path));
    }

    rowAt(index: number): MailLayoutRow | undefined {
        if (index < 0 || index >= this.length) return undefined;

        const block = this.blockAt(index);
        if (block === undefined) return undefined;

        const offset = index - block.start;
        if (offset < block.headers.length) return {kind: "header", node: block.headers[offset]};

        return {kind: "mail", path: block.leaf.path, offset: offset - block.headers.length};
    }

    /**
     * Which row the mail at [index] of the listing sits in, headers included.
     *
     * What a caller wants when it counts mails rather than rows -- how far a window reaches, or
     * where the mail it is scrolling to has ended up.
     */
    rowOfMail(index: number): number | undefined {
        if (index < 0 || index >= this.mailCount) return undefined;

        const block = this.blockOfMail(index);
        if (block === undefined) return undefined;

        return block.start + block.headers.length + (index - block.mailStart);
    }

    /** The mails of [index] rows, from [from] on, as the groups and offsets they are. */
    mailsIn(from: number, count: number): {path: string[]; offset: number}[] {
        const mails: {path: string[]; offset: number}[] = [];

        for (let row = from; row < from + count && row < this.length; row++) {
            const entry = this.rowAt(row);
            if (entry?.kind === "mail") mails.push({path: entry.path, offset: entry.offset});
        }

        return mails;
    }

    /** The last block that starts at or before [index]. */
    private blockAt(index: number): Block | undefined {
        let low = 0;
        let high = this.blocks.length - 1;
        let found: Block | undefined;

        while (low <= high) {
            const middle = (low + high) >> 1;
            const block = this.blocks[middle];

            if (block.start <= index) {
                found = block;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return found;
    }

    /** The same by mail position rather than by row. */
    private blockOfMail(index: number): Block | undefined {
        let low = 0;
        let high = this.blocks.length - 1;
        let found: Block | undefined;

        while (low <= high) {
            const middle = (low + high) >> 1;
            const block = this.blocks[middle];

            if (block.mailStart <= index) {
                found = block;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return found;
    }
}

/** How a group's path is held in a map. No separator a key of its own could carry. */
function pathKey(path: string[]): string {
    return path.join("\u0000");
}

/**
 * The tree the counts describe, in the order the table shows it.
 *
 * The server hands out one row per combination of keys that holds mail, in no order: what a group
 * is called and where it goes is decided here, because it is a question of wording -- and of
 * which of them a reader wants to see first, which is what a level's `reversed` says.
 */
export function buildLayout(groups: MailGroupCount[], levels: ViewGrouping[]): MailLayout {
    if (levels.length === 0) {
        const total = groups.reduce((sum, group) => sum + group.count, 0);
        return MailLayout.flat(total);
    }

    type Building = {key: string; count: number; children: Map<string, Building>};

    const roots = new Map<string, Building>();

    for (const group of groups) {
        let level = roots;
        for (let index = 0; index < levels.length; index++) {
            const key = group.keys[index] ?? "";
            const held = level.get(key);
            const node = held ?? {key, count: 0, children: new Map<string, Building>()};
            if (held === undefined) level.set(key, node);

            node.count += group.count;
            level = node.children;
        }
    }

    const build = (level: Map<string, Building>, depth: number, path: string[]): MailGroupNode[] => {
        const grouping = levels[depth];
        const sorted = [...level.values()].sort((one, other) => compareGroups(grouping.kind, one, other));
        if (grouping.reversed) sorted.reverse();

        return sorted.map((entry) => {
            const own = [...path, entry.key];

            return {
                path: own,
                level: depth,
                label: labelOf(grouping.kind, entry.key),
                count: entry.count,
                children: depth + 1 < levels.length ? build(entry.children, depth + 1, own) : [],
            };
        });
    };

    return new MailLayout(build(roots, 0, []), levels.length);
}

/**
 * Which of two groups of the same level comes first, before `reversed` is applied.
 *
 * Dates run newest first, states run in the order a reader works through them -- unread before
 * read, the inbox before the archive. Correspondents and accounts are ordered by how much mail
 * they hold: their names are not here, and an order by id would be no order at all.
 */
function compareGroups(
    kind: ViewGroupingKind,
    one: {key: string; count: number},
    other: {key: string; count: number}
): number {
    switch (kind) {
        case "date_smart": {
            const a = Number(one.key);
            const b = Number(other.key);
            const aNamed = a <= SMART_MONTH;
            const bNamed = b <= SMART_MONTH;
            // The named stretches first, in their own order; the months under them newest first.
            if (aNamed && bNamed) return a - b;
            if (aNamed !== bNamed) return aNamed ? -1 : 1;

            return b - a;
        }

        case "year":
        case "month":
            return Number(other.key) - Number(one.key);

        case "day":
            return other.key.localeCompare(one.key);

        case "read":
            // Unread first: a mailbox is read from what has not been.
            return (one.key === "true" ? 1 : 0) - (other.key === "true" ? 1 : 0);

        case "archived": {
            const order = ["Unarchive", "Archive", "Spam"];
            return order.indexOf(one.key) - order.indexOf(other.key);
        }

        case "sender":
        case "imap_account":
            return other.count - one.count || one.key.localeCompare(other.key);
    }
}

/** What the header of a group stands for, from the key the api gave it. */
function labelOf(kind: ViewGroupingKind, key: string): MailGroupLabel {
    switch (kind) {
        case "date_smart": {
            const bucket = Number(key);
            // A key that is not a number is no stretch this knows -- a server counting something
            // else, say. It is still a group and still has mail under it, so it gets a header of
            // what it plainly is rather than taking the listing down with it.
            if (!Number.isFinite(bucket)) return {kind: "day", date: key};
            if (bucket === SMART_TODAY) return {kind: "today"};
            if (bucket === 2) return {kind: "yesterday"};
            if (bucket === 3) return {kind: "week"};
            if (bucket === SMART_MONTH) return {kind: "month"};

            return monthLabel(bucket);
        }

        case "month":
            return Number.isFinite(Number(key)) ? monthLabel(Number(key)) : {kind: "day", date: key};

        case "year":
            return Number.isFinite(Number(key))
                ? {kind: "year", year: Number(key)}
                : {kind: "day", date: key};

        case "day":
            return {kind: "day", date: key};

        case "sender":
            return {kind: "sender", id: key};

        case "imap_account":
            return {kind: "account", id: key};

        case "read":
            return {kind: "read", isRead: key === "true"};

        case "archived":
            return {kind: "archived", state: key as ViewArchivedState};
    }
}

/** A month as the api counts it -- 202609 -- as the year and month a header reads. */
function monthLabel(number: number): MailGroupLabel {
    return {kind: "calendarMonth", year: Math.floor(number / 100), month: number % 100};
}
