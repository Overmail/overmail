/**
 * How a send time is shown: today it is only a time, everything older is a date.
 *
 * Which day that date is on is the row's group heading, so the cell does not name it again.
 */
export type SentAtLabel = {kind: "time"} | {kind: "date"; withYear: boolean};

/** Midnight of the day [date] falls on, in local time -- what "another day" is counted in. */
function startOfDay(date: Date): number {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

/**
 * Which of the two shapes fits [sent], measured against [now].
 *
 * Calendar days, not 24 hour windows: a mail from 23:30 is another day's half an hour later,
 * which is what a reader means by it. A send time in the future is a date -- clock skew makes
 * minutes of it, and anything beyond that is not "today" in any useful sense.
 */
export function sentAtLabel(sent: Date, now: Date): SentAtLabel {
    const days = Math.round((startOfDay(now) - startOfDay(sent)) / 86_400_000);

    if (days === 0) return {kind: "time"};

    return {kind: "date", withYear: sent.getFullYear() !== now.getFullYear()};
}
