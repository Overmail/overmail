/** What a listing can be cut into. `none` is the whole mailbox as one stretch. */
export type MailGrouping = "none" | "date";

/** From this day of the week on there is a stretch of the week that is neither today nor yesterday. */
const WEEK_BUCKET_FROM_ISO_DAY = 3;

const MS_PER_DAY = 86_400_000;

/**
 * A day as one number, counted from the epoch.
 *
 * Built through `Date.UTC` even for local dates: a local midnight shifts by an hour twice a year,
 * and a day number that is off by an hour is off by a day for half of them.
 */
const dayNumber = (year: number, monthIndex: number, day: number) =>
    Date.UTC(year, monthIndex, day) / MS_PER_DAY;

/** The stretch a mail is shown under. */
export type MailGroupLabel =
    | {kind: "today"}
    | {kind: "yesterday"}
    | {kind: "week"}
    | {kind: "month"}
    /** [month] is 1-12. */
    | {kind: "calendarMonth"; year: number; month: number};

/** A stretch of the listing. A label of null is a stretch with no header over it. */
export type MailGroup = {
    label: MailGroupLabel | null;
    count: number;
};

/** One stretch as the server counted it: `yyyy-mm-dd` for a day, null for an ungrouped listing. */
export type MailGroupCount = {
    key: string | null;
    count: number;
};

/**
 * Which stretch the day [key] belongs to, seen from [now].
 *
 * Today and yesterday are their own; then the rest of this week, but only from Wednesday on --
 * before that every day of the week is already today or yesterday. Then the rest of this month,
 * unless those days already reach the 1st. Everything older is its calendar month.
 */
export function labelFor(key: string, now: Date): MailGroupLabel {
    const [year, month, day] = key.split("-").map(Number);
    const number = dayNumber(year, month - 1, day);

    const today = dayNumber(now.getFullYear(), now.getMonth(), now.getDate());
    // A day in the future reads as today: that is clock skew, not a stretch of its own.
    if (number >= today) return {kind: "today"};
    if (number === today - 1) return {kind: "yesterday"};

    /** Monday is 1, which is the week this counts in. */
    const isoDay = ((now.getDay() + 6) % 7) + 1;
    const weekStart = today - (isoDay - 1);
    const monthStart = dayNumber(now.getFullYear(), now.getMonth(), 1);

    if (isoDay >= WEEK_BUCKET_FROM_ISO_DAY && number >= weekStart) return {kind: "week"};

    // Where the stretches above have got to. Without the week stretch that is yesterday.
    const covered = isoDay >= WEEK_BUCKET_FROM_ISO_DAY ? weekStart : today - 1;
    if (covered > monthStart && number >= monthStart) return {kind: "month"};

    return {kind: "calendarMonth", year, month};
}

function sameLabel(one: MailGroupLabel | null, other: MailGroupLabel | null): boolean {
    if (one === null || other === null) return one === other;
    if (one.kind !== other.kind) return false;
    if (one.kind !== "calendarMonth" || other.kind !== "calendarMonth") return true;

    return one.year === other.year && one.month === other.month;
}

/**
 * Folds the days the server counted into the stretches a reader is shown.
 *
 * One pass: the days come newest first and the stretches are cut along that order, so days that
 * belong together are next to each other and their counts simply add up.
 */
export function foldGroups(days: MailGroupCount[], now: Date): MailGroup[] {
    const groups: MailGroup[] = [];

    for (const day of days) {
        const label = day.key === null ? null : labelFor(day.key, now);
        const last = groups.at(-1);

        if (last !== undefined && sameLabel(last.label, label)) {
            last.count += day.count;
            continue;
        }

        groups.push({label, count: day.count});
    }

    return groups;
}

/** One row of the table: a header, or the mail at [index] of the mailbox. */
export type MailLayoutRow =
    | {kind: "header"; label: MailGroupLabel; count: number}
    | {kind: "mail"; index: number};

type Stretch = {
    label: MailGroupLabel | null;
    /** Where the stretch starts in the layout, its header included. */
    layoutStart: number;
    /** Where its first mail sits in the mailbox. */
    mailStart: number;
    count: number;
};

/**
 * Where every row of the table sits: which are headers, and which mail of the mailbox the others
 * hold.
 *
 * Nothing is materialised -- a mailbox with a hundred thousand mails would be a hundred thousand
 * objects for a screen that shows thirty. What is held is one entry per stretch, and a row is
 * found in it by halving.
 */
export class MailLayout {
    private readonly stretches: Stretch[] = [];

    /** How many rows the table has, headers included. */
    readonly length: number;

    /** How many of those rows are mails. */
    readonly mailCount: number;

    constructor(groups: MailGroup[]) {
        let layout = 0;
        let mail = 0;

        for (const group of groups) {
            this.stretches.push({
                label: group.label,
                layoutStart: layout,
                mailStart: mail,
                count: group.count,
            });
            layout += (group.label === null ? 0 : 1) + group.count;
            mail += group.count;
        }

        this.length = layout;
        this.mailCount = mail;
    }

    /** A listing without headers, which is what stands in until the stretches are known. */
    static flat(mails: number): MailLayout {
        return new MailLayout(mails === 0 ? [] : [{label: null, count: mails}]);
    }

    rowAt(index: number): MailLayoutRow | undefined {
        if (index < 0 || index >= this.length) return undefined;

        const stretch = this.stretchAt(index);
        if (stretch === undefined) return undefined;

        const header = stretch.label === null ? 0 : 1;
        const offset = index - stretch.layoutStart;

        if (header === 1 && offset === 0) {
            return {kind: "header", label: stretch.label!, count: stretch.count};
        }

        return {kind: "mail", index: stretch.mailStart + offset - header};
    }

    /** The last stretch that starts at or before [index]. */
    private stretchAt(index: number): Stretch | undefined {
        let low = 0;
        let high = this.stretches.length - 1;
        let found: Stretch | undefined;

        while (low <= high) {
            const middle = (low + high) >> 1;
            const stretch = this.stretches[middle];

            if (stretch.layoutStart <= index) {
                found = stretch;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return found;
    }
}
