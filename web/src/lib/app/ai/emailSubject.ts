/** Above this a subject is cut; up to it, it is shown whole. */
const MAX_LENGTH = 50;

/** What is left of a subject that was cut, before the ellipsis. */
const KEPT_LENGTH = 40;

/**
 * A subject as an inline chip shows it. Long ones are cut, so one mail cannot take a whole line
 * of an answer; the full text stays available as the chip's title.
 */
export function shortSubject(subject: string): string {
    if (subject.length <= MAX_LENGTH) return subject;

    // Trailing whitespace before the dots would read as a gap.
    return `${subject.slice(0, KEPT_LENGTH).trimEnd()}...`;
}
